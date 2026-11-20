package dev.qb2.cognition.planning;

import dev.qb2.client.config.Settings;
import dev.qb2.cognition.context.ContextBuilder;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.inference.HttpTransport;
import dev.qb2.inference.provider.ApiLanguageModel;
import dev.qb2.memory.*;
import dev.qb2.memory.nerve.NerveMemory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One bounded worker; jobs contain immutable snapshots and never read Minecraft state. */
public final class Mind implements AutoCloseable {
  public record Job(
      long epoch,
      String scope,
      String reason,
      Settings settings,
      String apiKey,
      String nerveKey,
      Map<String, Object> context,
      List<String> keywords,
      Set<String> targets,
      List<Episode> events) {}

  public record Outcome(
      long epoch,
      Decision decision,
      String error,
      int tokens,
      long millis,
      List<Episode> memories) {}

  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          r -> {
            Thread t = new Thread(r, "qb2-cognition");
            t.setDaemon(true);
            return t;
          });
  private final HttpTransport http = new HttpTransport();
  private final Path outboxPath;
  private PersistentOutbox outbox;
  private Future<?> task;
  public volatile String status = "idle", memoryStatus = "not connected";
  public volatile int pending, knowledgeHits;
  private volatile long epoch;

  public Mind(Path file) {
    outboxPath = file;
  }

  public boolean busy() {
    return task != null && !task.isDone();
  }

  public void cancel(long nextEpoch) {
    epoch = nextEpoch;
    if (task != null) task.cancel(true);
    worker.purge();
  }

  public void submit(Job job, Consumer<Outcome> receiver) {
    if (busy()) return;
    epoch = job.epoch();
    task =
        worker.submit(
            () -> {
              long started = System.nanoTime();
              try {
                if (outbox == null) outbox = new PersistentOutbox(outboxPath);
                var memory = new NerveMemory(http, job.settings().nerveEndpoint, job.nerveKey());
                try {
                  outbox.drain(memory);
                  memoryStatus = "connected";
                } catch (Exception e) {
                  memoryStatus = "unavailable; durable queue";
                  if (e instanceof InterruptedException) throw e;
                }
                if (outbox.size() > PersistentOutbox.CAPACITY - 8)
                  throw new IllegalStateException("Memory queue full; cognition paused");
                for (var event : job.events()) outbox.add(event);
                List<Episode> recalled = List.of();
                try {
                  recalled = memory.search(job.scope(), job.keywords(), job.settings().memoryTopK);
                  memoryStatus = "connected";
                } catch (InterruptedException e) {
                  throw e;
                } catch (Exception e) {
                  memoryStatus = "unavailable; durable queue";
                }
                var sections = new LinkedHashMap<>(job.context());
                sections.put("memories", recalled);
                knowledgeHits = 0;
                var knowledgeScope = job.context().get("knowledgeScope");
                if (knowledgeScope instanceof String catalog && catalog.matches("[a-f0-9]{64}")) {
                  try {
                    var facts = memory.search(catalog, job.keywords(), job.settings().memoryTopK);
                    knowledgeHits = facts.size();
                    sections.put(
                        "knowledge",
                        facts.stream()
                            .map(
                                e ->
                                    Map.of(
                                        "id",
                                        e.id(),
                                        "kind",
                                        e.kind(),
                                        "text",
                                        e.text(),
                                        "partial",
                                        e.text().contains("; PARTIAL:"),
                                        "source",
                                        "client_registry_or_synced_recipe"))
                            .toList());
                  } catch (InterruptedException e) {
                    throw e;
                  } catch (Exception e) {
                    sections.put("knowledge", List.of());
                  }
                }
                String context = new ContextBuilder().build(sections, job.settings().tokenBudget);
                status = "thinking";
                String response =
                    new ApiLanguageModel(http, job.settings(), job.apiKey()).complete(context);
                if (Thread.currentThread().isInterrupted() || epoch != job.epoch()) return;
                Decision parsed = Decision.parse(response, job.targets());
                Object recent = job.context().get("recentThoughts");
                String thought =
                    recent instanceof List<?> list && list.contains(parsed.thought())
                        ? ""
                        : parsed.thought();
                Decision decision =
                    new Decision(
                        parsed.action(),
                        parsed.target(),
                        thought,
                        Boolean.FALSE.equals(job.context().get("speechAllowed"))
                            ? ""
                            : parsed.speech(),
                        parsed.memory());
                if (!decision.thought().isBlank())
                  outbox.add(recollection(job, "thought", decision.thought()));
                if (!decision.speech().isBlank())
                  outbox.add(recollection(job, "speech", decision.speech()));
                if (!decision.memory().isBlank())
                  outbox.add(
                      recollection(
                          job,
                          "reflection",
                          "Model recollection (not verified): " + decision.memory()));
                pending = outbox.size();
                receiver.accept(
                    new Outcome(
                        job.epoch(),
                        decision,
                        "",
                        (context.length() + ContextBuilder.INSTRUCTION.length()) / 3,
                        (System.nanoTime() - started) / 1_000_000,
                        recalled));
                status = "ready";
                try {
                  outbox.drain(memory);
                  memoryStatus = "connected";
                } catch (InterruptedException e) {
                  throw e;
                } catch (Exception e) {
                  memoryStatus = "unavailable; durable queue";
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "cancelled";
              } catch (Exception e) {
                status = "error";
                // Never forward arbitrary exception messages or remote response bodies.
                String safe =
                    e instanceof IllegalStateException
                            && e.getMessage() != null
                            && e.getMessage().startsWith("Memory queue full")
                        ? "Memory queue full; cognition paused"
                        : job.apiKey().isBlank()
                            ? "API key missing"
                            : e instanceof java.net.http.HttpTimeoutException
                                ? "API timeout"
                                : e instanceof IllegalArgumentException
                                        || e instanceof com.google.gson.JsonParseException
                                    ? "Invalid model response or configuration"
                                    : e instanceof java.io.IOException
                                            && e.getMessage() != null
                                            && e.getMessage().matches("API HTTP [0-9]{3}")
                                        ? e.getMessage()
                                        : "Inference or memory operation failed";
                receiver.accept(
                    new Outcome(
                        job.epoch(),
                        null,
                        safe,
                        0,
                        (System.nanoTime() - started) / 1_000_000,
                        List.of()));
              } finally {
                if (outbox != null) pending = outbox.size();
              }
            });
  }

  private static Episode recollection(Job job, String kind, String text) {
    var keywords = new LinkedHashSet<>(dev.qb2.perception.Keywords.extract(text));
    keywords.addAll(job.keywords());
    return Episode.create(
        job.scope(),
        kind,
        keywords.stream().filter(word -> word.length() <= 200).limit(1000).toList(),
        text);
  }

  public void flush(Settings settings, String nerveKey) {
    if (busy()) return;
    task =
        worker.submit(
            () -> {
              try {
                if (outbox == null) outbox = new PersistentOutbox(outboxPath);
                if (outbox.size() > 0)
                  outbox.drain(new NerveMemory(http, settings.nerveEndpoint, nerveKey));
                memoryStatus = "connected";
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                memoryStatus = "unavailable; durable queue";
              } finally {
                if (outbox != null) pending = outbox.size();
              }
            });
  }

  public void close() {
    if (task != null) task.cancel(true);
    worker.shutdownNow();
    http.close();
  }
}
