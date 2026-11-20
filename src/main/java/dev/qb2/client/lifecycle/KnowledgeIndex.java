package dev.qb2.client.lifecycle;

import dev.qb2.inference.HttpTransport;
import dev.qb2.memory.nerve.NerveMemory;
import dev.qb2.perception.knowledge.CatalogReader;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

/** Bounded, regenerable catalog import. Never consumes the durable episodic outbox. */
public final class KnowledgeIndex implements AutoCloseable {
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var thread = new Thread(r, "qb2-knowledge");
            thread.setDaemon(true);
            return thread;
          });
  private final HttpTransport http = new HttpTransport();
  private CatalogReader reader;
  private final byte[] fingerprint = new byte[32];
  private int count;
  private boolean importing, exhausted, disabled;
  private Future<?> task;
  private List<dev.qb2.memory.Episode> retry = List.of();
  private volatile boolean acknowledged;
  private volatile long retryAfter;
  public volatile String status = "waiting for recipes", scope = "";
  public int written, partialRecipes;

  public void tick(Minecraft mc, String endpoint, String key) {
    if (disabled) return;
    try {
      advance(mc, endpoint, key);
    } catch (RuntimeException | LinkageError e) {
      disabled = true;
      status = "Catalog extraction failed; gameplay continues; reconnect to retry";
    }
  }

  private void advance(Minecraft mc, String endpoint, String key) {
    if (mc.level == null || mc.level.getRecipeManager().getRecipes().isEmpty()) return;
    if (task != null && !task.isDone()) return;
    if (acknowledged) {
      written += retry.size();
      retry = List.of();
      acknowledged = false;
    }
    if (System.nanoTime() < retryAfter) return;
    if (!retry.isEmpty()) {
      send(endpoint, key);
      return;
    }
    if (exhausted) {
      status = "ready: " + written + " facts; partial recipes " + partialRecipes;
      return;
    }
    if (reader == null) reader = new CatalogReader(mc);
    long deadline = System.nanoTime() + 2_000_000;
    var batch = new ArrayList<dev.qb2.memory.Episode>();
    int visited = 0;
    while (visited++ < 48 && System.nanoTime() < deadline && reader.hasNext()) {
      var fact = reader.next();
      if (importing) batch.add(fact.episode(scope));
      else {
        byte[] digest = digest(fact.kind() + "|" + fact.key() + "|" + fact.text());
        for (int i = 0; i < 32; i++) fingerprint[i] ^= digest[i];
        count++;
      }
    }
    if (!reader.hasNext()) {
      if (!importing) {
        scope =
            HexFormat.of()
                .formatHex(
                    digest(
                        "qb2:catalog:v1|" + count + "|" + HexFormat.of().formatHex(fingerprint)));
        reader = new CatalogReader(mc);
        importing = true;
      } else {
        exhausted = true;
        partialRecipes = reader.partialRecipes;
      }
    }
    status = importing ? "importing: " + written + "/" + count : "fingerprinting: " + count;
    if (!batch.isEmpty()) {
      retry = List.copyOf(batch);
      send(endpoint, key);
    }
  }

  private void send(String endpoint, String key) {
    var batch = retry;
    task =
        worker.submit(
            () -> {
              try {
                new NerveMemory(http, endpoint, key).writeBatch(batch);
                acknowledged = true;
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                status = "NERVE unavailable; catalog import will retry";
                retryAfter = System.nanoTime() + 20_000_000_000L;
              }
            });
  }

  private static byte[] digest(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 required", e);
    }
  }

  public void close() {
    if (task != null) task.cancel(true);
    worker.shutdownNow();
    http.close();
  }
}
