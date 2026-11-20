package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.qb2.client.config.Settings;
import dev.qb2.cognition.decisions.ActionQueue;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.cognition.planning.Mind;
import dev.qb2.cognition.tools.ToolSnapshot;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolCallingAndPriorityQueueTest {
  @Test
  void actionQueueSortsByPriorityWithFifoTiesAndBoundedCapacity() {
    var queue = new ActionQueue(3);
    assertTrue(queue.push("rest", "", 1));
    assertTrue(queue.push("explore", "", 5));
    assertTrue(queue.push("return", "", 5));
    // Capacity full (3): priority 0 should be rejected
    assertFalse(queue.push("observe", "", 0));
    // Priority 8 should evict lowest priority ("rest", prio 1)
    assertTrue(queue.push("inspect", "b1", 8));
    assertEquals(3, queue.size());

    assertEquals("inspect", queue.pollNext().orElseThrow().action());
    // FIFO among equal priority 5: explore before return
    assertEquals("explore", queue.pollNext().orElseThrow().action());
    assertEquals("return", queue.pollNext().orElseThrow().action());
    assertTrue(queue.pollNext().isEmpty());
  }

  @Test
  void decisionResolvesShortAliasesAndParsesPlanAndToolCalls() {
    var aliases = Map.of("b1", "create:mechanical_drill@10,64,5", "b2", "create:depot@12,64,5");
    var visible = Set.of("create:mechanical_drill@10,64,5", "create:depot@12,64,5");
    String json =
        """
        {
          "action": "go_to",
          "target": "b1",
          "thought": "Checking drill first",
          "speech": "Das ist ein Create Mechanical Drill.",
          "tool_calls": [{"tool": "inspect_details", "arg": "b1"}],
          "plan": [
            {"action": "inspect", "target": "b2", "priority": 4},
            {"action": "rest", "target": "", "priority": 1}
          ]
        }
        """;
    var d = Decision.parse(json, visible, aliases);
    assertEquals("create:mechanical_drill@10,64,5", d.target());
    assertEquals(1, d.toolCalls().size());
    assertEquals("inspect_details", d.toolCalls().get(0).tool());
    assertEquals(2, d.plan().size());
    assertEquals("create:depot@12,64,5", d.plan().get(0).target());
    assertEquals(4, d.plan().get(0).priority());
  }

  @Test
  void tolerantParserHandlesSmallLocalModelProseAndStandaloneRecipeLookup() {
    String noisy1bOutput =
        """
        Sure! Here is my JSON response for you:
        {"action":"chat_with_player","target":"unknown_block","thought":"Thinking locally","speech":"Hier ist die Antwort vom 1B Modell!","extra_key":123}
        Hope that helps!
        """;
    var d = Decision.parseTolerant(noisy1bOutput, Set.of(), Map.of());
    assertEquals("observe", d.action());
    assertEquals("", d.target());
    assertEquals("Hier ist die Antwort vom 1B Modell!", d.speech());

    var snap =
        new ToolSnapshot(
            Map.of(),
            Map.of(),
            "",
            "",
            "",
            null,
            List.of("Server-synced recipe minecraft:iron_ingot; output=minecraft:iron_ingot x1"));
    String recipeReply =
        dev.qb2.cognition.tools.ToolRegistry.executeOne(
            new dev.qb2.cognition.tools.ToolRegistry.Call("lookup_recipe", "iron_ingot"),
            snap,
            null,
            "scope");
    assertTrue(recipeReply.contains("output=minecraft:iron_ingot"));

    var netherEp =
        dev.qb2.memory.Episode.createAt(
            "a".repeat(64),
            "death_location",
            List.of("death"),
            "Died in Nether",
            new dev.qb2.companion.movement.Point(10, 64, -5),
            "minecraft:the_nether");
    assertTrue(netherEp.hasPosition());
    assertEquals("minecraft:the_nether", netherEp.dimension());
  }

  @Test
  void mindExecutesReadOnlyToolCallsAndFeedsResultsIntoSecondRound(@TempDir Path dir)
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> secondRequestBody = new AtomicReference<>("");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1beta/models/gemma-4-31b-it:generateContent",
        ex -> {
          String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          int n = calls.incrementAndGet();
          String replyJson;
          if (n == 1) {
            replyJson =
                """
                {"candidates":[{"content":{"parts":[{"text":"{\\"action\\":\\"observe\\",\\"target\\":\\"\\",\\"thought\\":\\"Need machine details\\",\\"speech\\":\\"\\",\\"tool_calls\\":[{\\"tool\\":\\"inspect_details\\",\\"arg\\":\\"b1\\"},{\\"tool\\":\\"scan_area\\",\\"arg\\":\\"\\"}]}"}]}}]}
                """;
          } else {
            secondRequestBody.set(req);
            replyJson =
                """
                {"candidates":[{"content":{"parts":[{"text":"{\\"action\\":\\"inspect\\",\\"target\\":\\"b1\\",\\"thought\\":\\"Create iron washing setup\\",\\"speech\\":\\"Der Mechanical Drill baut Cobblestone ab, das über Millstone und Fan Washing Catalyst auf dem Depot zu Eisen gewaschen wird.\\",\\"plan\\":[{\\"action\\":\\"rest\\",\\"target\\":\\"\\",\\"priority\\":1}]}"}]}}]}
                """;
          }
          byte[] bytes = replyJson.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();

    var tools =
        new ToolSnapshot(
            Map.of("b1", "create:mechanical_drill@10,64,5"),
            Map.of("b1", "Mechanical Drill [create:mechanical_drill] at 10,64,5 | state: facing=north"),
            "Mechanical Drill, Millstone, Fan Washing Catalyst, Depot, Barrel",
            "Player: 32x Iron Ingot",
            "",
            null);

    try (Mind mind = new Mind(dir.resolve("outbox.json"))) {
      Settings s = new Settings();
      s.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
      s.nerveEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      s.model = "gemma-4-31b-it";
      s.modelChain = "";
      s.ambientModel = "";
      s.timeoutSeconds = 5;

      var latch = new CountDownLatch(1);
      var outRef = new CompletableFuture<Mind.Outcome>();
      mind.submit(
          new Mind.Job(
              1,
              "e".repeat(64),
              "direct address",
              s,
              "test-key",
              "nerve-key",
              Map.of("speechAllowed", true),
              List.of("drill"),
              Set.of("create:mechanical_drill@10,64,5"),
              List.of(),
              "",
              tools),
          out -> {
            outRef.complete(out);
            latch.countDown();
          });
      assertTrue(latch.await(15, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertTrue(secondRequestBody.get().contains("Fan Washing Catalyst"));
      assertTrue(secondRequestBody.get().contains("create:mechanical_drill"));
      var outcome = outRef.get();
      assertNotNull(outcome.decision());
      assertEquals("create:mechanical_drill@10,64,5", outcome.decision().target());
      assertTrue(outcome.decision().speech().contains("Fan Washing Catalyst"));
      assertEquals(1, outcome.decision().plan().size());
    } finally {
      server.stop(0);
    }
  }
}
