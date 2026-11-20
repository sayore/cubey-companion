package dev.qb2.cognition.planning;

import dev.qb2.client.config.Settings;
import dev.qb2.cognition.context.ContextBuilder;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.cognition.tools.ToolRegistry;
import dev.qb2.cognition.tools.ToolSnapshot;
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
      List<Episode> events,
      String fallbackApiKey,
      ToolSnapshot tools) {
    public Job(
        long epoch,
        String scope,
        String reason,
        Settings settings,
        String apiKey,
        String nerveKey,
        Map<String, Object> context,
        List<String> keywords,
        Set<String> targets,
        List<Episode> events) {
      this(
          epoch,
          scope,
          reason,
          settings,
          apiKey,
          nerveKey,
          context,
          keywords,
          targets,
          events,
          "",
          ToolSnapshot.EMPTY);
    }

    public Job(
        long epoch,
        String scope,
        String reason,
        Settings settings,
        String apiKey,
        String nerveKey,
        Map<String, Object> context,
        List<String> keywords,
        Set<String> targets,
        List<Episode> events,
        String fallbackApiKey) {
      this(
          epoch,
          scope,
          reason,
          settings,
          apiKey,
          nerveKey,
          context,
          keywords,
          targets,
          events,
          fallbackApiKey,
          ToolSnapshot.EMPTY);
    }
  }

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
  private final ExecutorService retrievalWorker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "qb2-retrieval");
            t.setDaemon(true);
            return t;
          });
  private final HttpTransport http = new HttpTransport();
  private final Path outboxPath;
  private final LocalFallbackMemory localFallback;
  private PersistentOutbox outbox;
  private Future<?> task;
  public volatile String status = "idle", memoryStatus = "not connected", activeModel = "";
  public volatile int pending, knowledgeHits;
  private volatile long epoch;
  private volatile long cooldownUntilMs;
  private volatile int consecutiveTransientErrors;

  public Mind(Path file) {
    outboxPath = file;
    Path parent = file.toAbsolutePath().getParent();
    localFallback =
        new LocalFallbackMemory(
            (parent == null ? Path.of(".") : parent).resolve("qb2-local-memory.json"));
  }

  public boolean inCooldown() {
    return System.currentTimeMillis() < cooldownUntilMs;
  }

  public long cooldownRemainingSeconds() {
    long rem = cooldownUntilMs - System.currentTimeMillis();
    return rem <= 0 ? 0 : (rem + 999) / 1000;
  }

  public void resetBackoff() {
    consecutiveTransientErrors = 0;
    cooldownUntilMs = 0;
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
                localFallback.writeAll(job.events());
                var knowledgeScope = job.context().get("knowledgeScope");
                Future<List<Episode>> knowledgeFuture = null;
                if (knowledgeScope instanceof String catalog && catalog.matches("[a-f0-9]{64}")) {
                  knowledgeFuture =
                      retrievalWorker.submit(
                          () -> memory.search(catalog, job.keywords(), job.settings().memoryTopK));
                }
                dev.qb2.companion.movement.Point playerPos = extractPlayerPos(job.context());
                List<Episode> recalled = List.of();
                List<NerveMemory.NearbyHit> nearbyHits = List.of();
                try {
                  recalled = memory.search(job.scope(), job.keywords(), job.settings().memoryTopK);
                  if (playerPos != null) {
                    try {
                      nearbyHits = memory.nearby(job.scope(), playerPos, 18.0, 4);
                    } catch (InterruptedException e) {
                      throw e;
                    } catch (Exception ignored) {
                      nearbyHits = localFallback.nearby(job.scope(), playerPos, 18.0, 4);
                    }
                  }
                  memoryStatus = "connected";
                } catch (InterruptedException e) {
                  if (knowledgeFuture != null) knowledgeFuture.cancel(true);
                  throw e;
                } catch (Exception e) {
                  memoryStatus = "unavailable; durable queue";
                  recalled =
                      localFallback.search(job.scope(), job.keywords(), job.settings().memoryTopK);
                  if (playerPos != null) {
                    nearbyHits = localFallback.nearby(job.scope(), playerPos, 18.0, 4);
                  }
                }
                var sections = new LinkedHashMap<>(job.context());
                sections.put("memories", recalled);
                if (!nearbyHits.isEmpty()) {
                  sections.put(
                      "nearbySpatial",
                      nearbyHits.stream()
                          .map(
                              h ->
                                  Map.of(
                                      "kind",
                                      h.episode().kind(),
                                      "distanceBlocks",
                                      h.distance(),
                                      "text",
                                      h.episode().text()))
                          .toList());
                }
                knowledgeHits = 0;
                if (knowledgeFuture != null) {
                  try {
                    var facts = knowledgeFuture.get(6, TimeUnit.SECONDS);
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
                    knowledgeFuture.cancel(true);
                    throw e;
                  } catch (Exception e) {
                    knowledgeFuture.cancel(true);
                    sections.put("knowledge", List.of());
                  }
                }
                boolean ambient = !"direct address".equals(job.reason());
                int effectiveBudget =
                    ambient
                        ? Math.min(job.settings().tokenBudget, 700)
                        : job.settings().tokenBudget;
                ToolSnapshot toolSnapshot = job.tools() == null ? ToolSnapshot.EMPTY : job.tools();
                var builder = new ContextBuilder();
                String context = builder.build(sections, effectiveBudget);
                status = "thinking";
                var model =
                    new ApiLanguageModel(
                        http, job.settings(), job.apiKey(), job.fallbackApiKey(), ambient);
                String response = model.complete(context);
                activeModel = model.lastUsedModel();
                resetBackoff();
                if (Thread.currentThread().isInterrupted() || epoch != job.epoch()) return;
                Decision parsed =
                    Decision.parse(response, job.targets(), toolSnapshot.aliasToFullKey());

                // Bounded read-only tool-calling loop (max 2 tool rounds).
                for (int round = 0; round < 2 && !parsed.toolCalls().isEmpty(); round++) {
                  var toolResults =
                      ToolRegistry.executeAll(parsed.toolCalls(), toolSnapshot, memory, job.scope());
                  sections.put("tool_results", toolResults);
                  context = builder.build(sections, Math.max(effectiveBudget, 1100));
                  response = model.complete(context);
                  activeModel = model.lastUsedModel();
                  if (Thread.currentThread().isInterrupted() || epoch != job.epoch()) return;
                  parsed = Decision.parse(response, job.targets(), toolSnapshot.aliasToFullKey());
                }

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
                        parsed.memory(),
                        List.of(),
                        parsed.plan());
                var newRecollections = new ArrayList<Episode>(3);
                if (!decision.thought().isBlank())
                  newRecollections.add(recollection(job, "thought", decision.thought()));
                if (!decision.speech().isBlank())
                  newRecollections.add(recollection(job, "speech", decision.speech()));
                if (!decision.memory().isBlank())
                  newRecollections.add(
                      recollection(
                          job,
                          "reflection",
                          "Model recollection (not verified): " + decision.memory()));
                for (Episode rec : newRecollections) outbox.add(rec);
                localFallback.writeAll(newRecollections);
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
                boolean transientApiError =
                    safe.equals("API timeout")
                        || safe.equals("API HTTP 429")
                        || safe.matches("API HTTP 5[0-9]{2}");
                if (transientApiError) {
                  int streak = ++consecutiveTransientErrors;
                  long delayMs = Math.min(120_000L, 15_000L * (1L << Math.min(streak - 1, 3)));
                  cooldownUntilMs = System.currentTimeMillis() + delayMs;
                  status = "backoff(" + (delayMs / 1000) + "s)";
                }
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

  private static dev.qb2.companion.movement.Point extractPlayerPos(Map<String, Object> context) {
    Object p = context.get("player");
    if (p instanceof Map<?, ?> map && map.get("position") instanceof dev.qb2.companion.movement.Point pt) {
      return pt;
    }
    return null;
  }

  private static Episode recollection(Job job, String kind, String text) {
    var keywords = new LinkedHashSet<>(dev.qb2.perception.Keywords.extract(text));
    keywords.addAll(job.keywords());
    return Episode.createAt(
        job.scope(),
        kind,
        keywords.stream().filter(word -> word.length() <= 200).limit(1000).toList(),
        text,
        extractPlayerPos(job.context()));
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
    retrievalWorker.shutdownNow();
    http.close();
  }
}
