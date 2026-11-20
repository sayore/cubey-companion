package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.qb2.client.config.Configuration;
import dev.qb2.client.config.Settings;
import dev.qb2.cognition.planning.Mind;
import dev.qb2.inference.HttpTransport;
import dev.qb2.inference.provider.ApiLanguageModel;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FallbackAndBackoffTest {
  @Test
  void failsOverFromPrimary503ToFallbackModel() throws Exception {
    AtomicInteger primaryHits = new AtomicInteger();
    AtomicInteger fallbackHits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1beta/models/gemma-4-31b-it:generateContent",
        ex -> {
          primaryHits.incrementAndGet();
          byte[] body = "unavailable".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(503, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v1/chat/completions",
        ex -> {
          fallbackHits.incrementAndGet();
          byte[] body =
              """
              {"choices":[{"message":{"content":"{\\"action\\":\\"observe\\",\\"target\\":\\"\\",\\"thought\\":\\"Fallback active\\",\\"speech\\":\\"Hallo vom Fallback!\\"}"}}]}
              """
                  .getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try (HttpTransport http = new HttpTransport()) {
      int port = server.getAddress().getPort();
      Settings s = new Settings();
      s.provider = "google";
      s.endpoint = "http://127.0.0.1:" + port + "/v1beta";
      s.model = "gemma-4-31b-it";
      s.modelChain = "";
      s.ambientModel = "";
      s.fallbackProvider = "openai";
      s.fallbackEndpoint = "http://127.0.0.1:" + port + "/v1";
      s.fallbackModel = "local-fallback";
      s.timeoutSeconds = 5;
      s.validate();

      var model = new ApiLanguageModel(http, s, "primary-key", "fallback-key");
      String reply = model.complete("hello");
      assertTrue(reply.contains("Fallback active"));
      assertEquals("fallback:local-fallback", model.lastUsedModel());
      assertTrue(primaryHits.get() >= 1);
      assertEquals(1, fallbackHits.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void doesNotFailOverOnAuth401() throws Exception {
    AtomicInteger fallbackHits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1beta/models/gemma-4-31b-it:generateContent",
        ex -> {
          byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(401, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v1/chat/completions",
        ex -> {
          fallbackHits.incrementAndGet();
          ex.sendResponseHeaders(200, 0);
          ex.close();
        });
    server.start();
    try (HttpTransport http = new HttpTransport()) {
      int port = server.getAddress().getPort();
      Settings s = new Settings();
      s.endpoint = "http://127.0.0.1:" + port + "/v1beta";
      s.modelChain = "";
      s.ambientModel = "";
      s.fallbackProvider = "openai";
      s.fallbackEndpoint = "http://127.0.0.1:" + port + "/v1";
      s.fallbackModel = "local-fallback";
      var model = new ApiLanguageModel(http, s, "bad-key", "fallback-key");
      assertThrows(Exception.class, () -> model.complete("hello"));
      assertEquals(0, fallbackHits.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mindEntersBackoffOn503AndSavesFallbackSecrets(@TempDir Path dir) throws Exception {
    Configuration cfg = new Configuration(dir);
    Settings s = new Settings();
    s.fallbackProvider = "openai";
    s.fallbackEndpoint = "http://127.0.0.1:11434/v1";
    s.fallbackModel = "qwen3:8b";
    s.sounds = false;
    s.showTargetBeam = true;
    cfg.save(s, "k-primary", "k-nerve", "k-fallback");

    Configuration reloaded = new Configuration(dir);
    reloaded.load();
    assertEquals("k-primary", reloaded.apiKey());
    assertEquals("k-nerve", reloaded.nerveKey());
    assertEquals("k-fallback", reloaded.fallbackApiKey());
    assertEquals("qwen3:8b", reloaded.settings().fallbackModel);
    assertFalse(reloaded.settings().sounds);

    Settings styled = reloaded.settings().copy();
    styled.style = "x".repeat(200);
    styled.validate();
    assertEquals(120, styled.style.length());
    styled.style = "   ";
    styled.validate();
    assertEquals("calm", styled.style);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          byte[] body = "overloaded".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(503, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try (Mind mind = new Mind(dir.resolve("outbox.json"))) {
      Settings failing = new Settings();
      failing.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
      failing.nerveEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      failing.timeoutSeconds = 3;
      var latch = new CountDownLatch(1);
      var outcomeRef = new CompletableFuture<Mind.Outcome>();
      mind.submit(
          new Mind.Job(
              1,
              "0".repeat(64),
              "curiosity",
              failing,
              "key",
              "nerve",
              Map.of(),
              List.of("stone"),
              Set.of(),
              List.of()),
          out -> {
            outcomeRef.complete(out);
            latch.countDown();
          });
      assertTrue(latch.await(15, TimeUnit.SECONDS));
      assertEquals("API HTTP 503", outcomeRef.get().error());
      assertTrue(mind.inCooldown());
      assertTrue(mind.cooldownRemainingSeconds() >= 1);
      mind.resetBackoff();
      assertFalse(mind.inCooldown());
    } finally {
      server.stop(0);
    }
  }
}
