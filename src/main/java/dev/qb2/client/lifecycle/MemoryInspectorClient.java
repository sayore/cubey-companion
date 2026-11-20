package dev.qb2.client.lifecycle;

import dev.qb2.client.config.Settings;
import dev.qb2.inference.HttpTransport;
import dev.qb2.memory.Episode;
import dev.qb2.memory.nerve.NerveMemory;
import dev.qb2.perception.Keywords;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Async client helper for searching and deleting NERVE memories without blocking the UI thread. */
public final class MemoryInspectorClient implements AutoCloseable {
  private final HttpTransport http = new HttpTransport();
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "qb2-memory-inspector");
            t.setDaemon(true);
            return t;
          });

  public void searchAsync(
      Minecraft mc,
      Settings settings,
      String nerveKey,
      String scope,
      String query,
      Consumer<List<Episode>> cb) {
    if (scope == null || scope.isBlank()) {
      cb.accept(List.of());
      return;
    }
    List<String> words = Keywords.extract(query == null ? "" : query);
    if (words.isEmpty()) words = List.of("session", "container", "player", "block");
    List<String> finalWords = words;
    executor.submit(
        () -> {
          try {
            var mem = new NerveMemory(http, settings.nerveEndpoint, nerveKey);
            var results = mem.search(scope, finalWords, 12);
            mc.execute(() -> cb.accept(results));
          } catch (Exception e) {
            mc.execute(() -> cb.accept(List.of()));
          }
        });
  }

  public void deleteAsync(
      Minecraft mc,
      Settings settings,
      String nerveKey,
      String scope,
      String id,
      Consumer<Boolean> cb) {
    if (scope == null || scope.isBlank() || id == null || id.isBlank()) {
      cb.accept(false);
      return;
    }
    executor.submit(
        () -> {
          try {
            var mem = new NerveMemory(http, settings.nerveEndpoint, nerveKey);
            mem.delete(scope, id);
            mc.execute(() -> cb.accept(true));
          } catch (Exception e) {
            mc.execute(() -> cb.accept(false));
          }
        });
  }

  @Override
  public void close() {
    executor.shutdownNow();
    http.close();
  }
}
