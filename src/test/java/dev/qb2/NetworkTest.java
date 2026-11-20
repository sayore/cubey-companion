package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.qb2.client.config.Settings;
import dev.qb2.cognition.planning.Mind;
import dev.qb2.inference.HttpTransport;
import dev.qb2.inference.provider.ApiLanguageModel;
import dev.qb2.memory.*;
import dev.qb2.memory.nerve.NerveMemory;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NetworkTest {
  @TempDir Path temp;
  HttpServer server;
  HttpTransport http;
  String base;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(
        Executors.newCachedThreadPool(
            r -> {
              var t = new Thread(r);
              t.setDaemon(true);
              return t;
            }));
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    http = new HttpTransport();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    http.close();
  }

  @Test
  void insecureRemoteEndpointsAndEmbeddedCredentialsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> HttpTransport.validateEndpoint("http://example.com"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpTransport.validateEndpoint("https://user:password@example.com"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HttpTransport.validateEndpoint("https://example.com/?key=secret"));
    assertDoesNotThrow(() -> HttpTransport.validateEndpoint(base));
  }

  @Test
  void retriesTransientFailures() throws Exception {
    var count = new AtomicInteger();
    server.createContext(
        "/test",
        e -> {
          int n = count.incrementAndGet();
          byte[] b = "ok".getBytes();
          e.sendResponseHeaders(n < 3 ? 503 : 200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    assertEquals("ok", http.post(base + "/test", "", false, "{}", 2));
    assertEquals(3, count.get());
  }

  @Test
  void authenticationFailuresDoNotRetryOrExposeBody() {
    var count = new AtomicInteger();
    server.createContext(
        "/test",
        e -> {
          count.incrementAndGet();
          byte[] b = "private-key-body".getBytes();
          e.sendResponseHeaders(401, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    Exception error =
        assertThrows(
            java.io.IOException.class,
            () -> http.post(base + "/test", "sensitive", false, "{}", 2));
    assertEquals("API HTTP 401", error.getMessage());
    assertEquals(1, count.get());
  }

  @Test
  void googleRequestUsesNativeApiAndNeverPutsKeyInUrl() throws Exception {
    AtomicReference<JsonObject> captured = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    server.createContext(
        "/models/gemma-4-31b-it:generateContent",
        e -> {
          auth.set(e.getRequestHeaders().getFirst("x-goog-api-key"));
          assertNull(e.getRequestURI().getQuery());
          captured.set(
              JsonParser.parseString(new String(e.getRequestBody().readAllBytes()))
                  .getAsJsonObject());
          byte[] b =
              "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"action\\\":\\\"rest\\\"}\"}]}}]}"
                  .getBytes();
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    var settings = new Settings();
    settings.endpoint = base;
    assertEquals(
        "{\"action\":\"rest\"}",
        new ApiLanguageModel(http, settings, "local-test-key").complete("{}"));
    assertEquals("local-test-key", auth.get());
    assertTrue(captured.get().has("contents"));
    assertTrue(captured.get().has("systemInstruction"));
    assertEquals(
        "minimal",
        captured
            .get()
            .getAsJsonObject("generationConfig")
            .getAsJsonObject("thinkingConfig")
            .get("thinkingLevel")
            .getAsString());
  }

  @Test
  void openAiProtocolIsSupported() throws Exception {
    server.createContext(
        "/chat/completions",
        e -> {
          assertEquals("Bearer key", e.getRequestHeaders().getFirst("Authorization"));
          byte[] b = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}".getBytes();
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    var settings = new Settings();
    settings.provider = "openai";
    settings.endpoint = base;
    assertEquals("hello", new ApiLanguageModel(http, settings, "key").complete("{}"));
  }

  @Test
  void stalledResponseBodyTimesOut() {
    server.createContext(
        "/stall",
        e -> {
          e.sendResponseHeaders(200, 100);
          try {
            Thread.sleep(10000);
          } catch (InterruptedException ignored) {
          }
          e.close();
        });
    long start = System.nanoTime();
    assertThrows(java.io.IOException.class, () -> http.post(base + "/stall", "", false, "{}", 1));
    assertTrue((System.nanoTime() - start) / 1e9 < 7);
  }

  @Test
  void oversizedResponsesAreRejected() {
    server.createContext(
        "/large",
        e -> {
          byte[] b = new byte[300000];
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          } catch (java.io.IOException ignored) {
          }
        });
    assertThrows(java.io.IOException.class, () -> http.post(base + "/large", "", false, "{}", 2));
  }

  @Test
  void cancellationInterruptsRequestPromptly() throws Exception {
    server.createContext(
        "/slow",
        e -> {
          try {
            Thread.sleep(10000);
          } catch (InterruptedException ignored) {
          }
          e.close();
        });
    var done = new CountDownLatch(1);
    Thread thread =
        new Thread(
            () -> {
              try {
                http.post(base + "/slow", "", false, "{}", 25);
              } catch (Exception e) {
                assertTrue(e instanceof InterruptedException);
              } finally {
                done.countDown();
              }
            });
    thread.start();
    Thread.sleep(150);
    thread.interrupt();
    assertTrue(done.await(2, TimeUnit.SECONDS));
  }

  @Test
  void outboxDrainsAfterRemoteAcknowledgement() throws Exception {
    var received = new AtomicInteger();
    server.createContext(
        "/memories",
        e -> {
          received.incrementAndGet();
          e.sendResponseHeaders(200, 2);
          try (var out = e.getResponseBody()) {
            out.write("{}".getBytes());
          }
        });
    Path file = temp.resolve("outbox.json");
    var box = new PersistentOutbox(file);
    box.add(Episode.create("a".repeat(64), "thought", List.of("iron"), "thought"));
    box.drain(new NerveMemory(http, base, "key"));
    assertEquals(1, received.get());
    assertEquals(0, new PersistentOutbox(file).size());
  }

  @Test
  void retrievalFiltersOtherWorldsAndDuplicateTexts() throws Exception {
    var good = Episode.create("a".repeat(64), "thought", List.of("iron"), "iron press");
    var wrong = Episode.create("b".repeat(64), "thought", List.of("iron"), "other world");
    server.createContext(
        "/search",
        e -> {
          byte[] b = new Gson().toJson(List.of(wrong, good, good)).getBytes();
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    assertEquals(
        List.of(good),
        new NerveMemory(http, base, "key").search("a".repeat(64), List.of("iron"), 3));
  }

  @Test
  void cognitionRetrievesCatalogAndPersistsPrivateRecollectionBeforeApplyingDecision()
      throws Exception {
    var stored = new CopyOnWriteArrayList<Episode>();
    var sentContext = new AtomicReference<JsonObject>();
    server.createContext(
        "/memories",
        e -> {
          stored.add(
              new Gson().fromJson(new String(e.getRequestBody().readAllBytes()), Episode.class));
          e.sendResponseHeaders(200, 2);
          try (var out = e.getResponseBody()) {
            out.write("{}".getBytes());
          }
        });
    server.createContext(
        "/search",
        e -> {
          var query =
              JsonParser.parseString(new String(e.getRequestBody().readAllBytes()))
                  .getAsJsonObject();
          var facts =
              query.get("scope").getAsString().equals("b".repeat(64))
                  ? List.of(
                      new Episode(
                          UUID.randomUUID().toString(),
                          "b".repeat(64),
                          "knowledge_recipe",
                          List.of("iron"),
                          "Synced press recipe; PARTIAL: energy unknown.",
                          "1970-01-01T00:00:00Z"))
                  : List.of();
          byte[] body = new Gson().toJson(facts).getBytes();
          e.sendResponseHeaders(200, body.length);
          try (var out = e.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/chat/completions",
        e -> {
          var request =
              JsonParser.parseString(new String(e.getRequestBody().readAllBytes()))
                  .getAsJsonObject();
          sentContext.set(
              JsonParser.parseString(
                      request
                          .getAsJsonArray("messages")
                          .get(1)
                          .getAsJsonObject()
                          .get("content")
                          .getAsString())
                  .getAsJsonObject());
          String decision =
              "{\"action\":\"inspect\",\"target\":\"press@1\",\"thought\":\"Iron is processed"
                  + " here.\",\"speech\":\"What are you making?\",\"memory\":\"First iron at the"
                  + " press.\"}";
          byte[] b =
              new Gson()
                  .toJson(
                      Map.of("choices", List.of(Map.of("message", Map.of("content", decision)))))
                  .getBytes();
          e.sendResponseHeaders(200, b.length);
          try (var out = e.getResponseBody()) {
            out.write(b);
          }
        });
    var settings = new Settings();
    settings.provider = "openai";
    settings.endpoint = base;
    settings.nerveEndpoint = base;
    Path file = temp.resolve("outbox.json");
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Mind.Outcome> outcome = new AtomicReference<>();
    try (var mind = new Mind(file)) {
      var job =
          new Mind.Job(
              1,
              "a".repeat(64),
              "direct address",
              settings,
              "test",
              "test",
              Map.of("world", "press", "knowledgeScope", "b".repeat(64)),
              List.of("create:mechanical_press"),
              Set.of("press@1"),
              List.of());
      mind.submit(
          job,
          o -> {
            try {
              assertEquals(3, new PersistentOutbox(file).size());
              outcome.set(o);
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              done.countDown();
            }
          });
      assertTrue(done.await(10, TimeUnit.SECONDS));
      assertNotNull(outcome.get());
      assertEquals(1, mind.knowledgeHits);
      assertTrue(outcome.get().memories().isEmpty());
      assertTrue(
          sentContext
              .get()
              .getAsJsonArray("knowledge")
              .get(0)
              .getAsJsonObject()
              .get("partial")
              .getAsBoolean());
      assertEquals("inspect", outcome.get().decision().action());
      for (int i = 0; i < 50 && mind.busy(); i++) Thread.sleep(20);
      assertEquals(3, stored.size());
      assertTrue(
          stored.stream()
              .anyMatch(
                  e ->
                      e.kind().equals("speech")
                          && e.keywords().contains("making")
                          && e.keywords().contains("create:mechanical_press")));
      assertTrue(
          stored.stream()
              .anyMatch(
                  e -> e.kind().equals("reflection") && e.text().startsWith("Model recollection")));
      assertEquals(0, new PersistentOutbox(file).size());
    }
  }
}
