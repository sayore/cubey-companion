package dev.qb2;

import dev.qb2.client.config.Settings;
import dev.qb2.cognition.context.ContextBuilder;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.inference.HttpTransport;
import dev.qb2.inference.provider.ApiLanguageModel;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit opt-in real API check; never part of the normal build or test suite. */
public final class LiveApiCheck {
  public static void main(String[] args) {
    String key = System.getenv("GOOGLE_API_KEY");
    if (key == null || key.isBlank()) {
      System.err.println("GOOGLE_API_KEY missing; no API request sent.");
      System.exit(2);
    }
    Settings settings = new Settings();
    settings.model = System.getenv().getOrDefault("QB2_MODEL", settings.model);
    settings.endpoint = System.getenv().getOrDefault("QB2_API_ENDPOINT", settings.endpoint);
    try (var http = new HttpTransport()) {
      settings.validate();
      String context =
          new ContextBuilder()
              .build(
                  Map.of(
                      "speechAllowed",
                      true,
                      "request",
                      Map.of(
                          "reason",
                          "direct address",
                          "message",
                          "Hallo QB2. Antworte kurz auf Deutsch, ohne ein Objekt zu erfinden."),
                      "world",
                      Map.of("dimension", "minecraft:overworld", "observations", List.of()),
                      "chat",
                      List.of(),
                      "memories",
                      List.of()),
                  settings.tokenBudget);
      long start = System.nanoTime();
      String response = new ApiLanguageModel(http, settings, key).complete(context);
      Decision decision = Decision.parse(response, Set.of());
      if (decision.speech().isBlank() && decision.thought().isBlank()) {
        throw new IllegalArgumentException("Empty cognitive response");
      }
      System.out.println(
          "PASS: real Google API call and QB2 decision validation ("
              + (System.nanoTime() - start) / 1_000_000
              + " ms). Key and response text withheld.");
    } catch (Exception error) {
      String message = error.getMessage();
      String safe =
          message != null && message.matches("API HTTP [0-9]{3}")
              ? message
              : "Transport, configuration or model response validation failed";
      System.err.println("Live API check failed: " + safe + ". No key or response body printed.");
      System.exit(1);
    }
  }
}
