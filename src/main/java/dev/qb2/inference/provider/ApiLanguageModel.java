package dev.qb2.inference.provider;

import com.google.gson.*;
import dev.qb2.client.config.Settings;
import dev.qb2.cognition.context.ContextBuilder;
import dev.qb2.inference.HttpTransport;
import java.util.*;

public final class ApiLanguageModel implements LanguageModel {
  private final HttpTransport http;
  private final Settings settings;
  private final String key;
  private final String fallbackKey;
  private final Gson json = new Gson();
  private volatile String lastUsedModel = "";

  public ApiLanguageModel(HttpTransport http, Settings settings, String key) {
    this(http, settings, key, "");
  }

  public ApiLanguageModel(HttpTransport http, Settings settings, String key, String fallbackKey) {
    this.http = http;
    this.settings = settings;
    this.key = key == null ? "" : key;
    this.fallbackKey = fallbackKey == null ? "" : fallbackKey;
  }

  public String lastUsedModel() {
    return lastUsedModel;
  }

  public String complete(String context) throws Exception {
    try {
      String reply =
          completeWith(settings.provider, settings.endpoint, settings.model, key, context);
      lastUsedModel = settings.model;
      return reply;
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception primaryError) {
      if (!settings.hasFallback() || !shouldFallback(primaryError)) throw primaryError;
      String fp = settings.fallbackProvider.isBlank() ? settings.provider : settings.fallbackProvider;
      String fe = settings.fallbackEndpoint.isBlank() ? settings.endpoint : settings.fallbackEndpoint;
      String fk = fallbackKey.isBlank() ? key : fallbackKey;
      String reply = completeWith(fp, fe, settings.fallbackModel, fk, context);
      lastUsedModel = "fallback:" + settings.fallbackModel;
      return reply;
    }
  }

  private static boolean shouldFallback(Exception error) {
    if (error instanceof java.io.IOException) {
      String msg = error.getMessage();
      // Do not fail over on auth/bad-request unless it's rate-limit (429) or server error (5xx) or transport error.
      if (msg != null && msg.matches("API HTTP 4[0-9]{2}") && !msg.equals("API HTTP 429"))
        return false;
      return true;
    }
    return error instanceof IllegalStateException
        && "API key missing".equals(error.getMessage());
  }

  private String completeWith(
      String provider, String endpoint, String model, String apiKey, String context)
      throws Exception {
    if (apiKey.isBlank()) throw new IllegalStateException("API key missing");
    String base = endpoint.replaceAll("/+$", "");
    boolean google = provider.equals("google");
    Object body;
    String url;
    if (google) {
      // Gemma 4 native API supports a separate system instruction.
      var generation = new LinkedHashMap<String, Object>();
      generation.put("maxOutputTokens", settings.outputTokens);
      generation.put("temperature", 0.65);
      if (model.replaceFirst("^models/", "").startsWith("gemma-4-"))
        generation.put("thinkingConfig", Map.of("thinkingLevel", "minimal"));
      body =
          Map.of(
              "systemInstruction",
              Map.of("parts", List.of(Map.of("text", ContextBuilder.INSTRUCTION))),
              "contents",
              List.of(Map.of("role", "user", "parts", List.of(Map.of("text", context)))),
              "generationConfig",
              generation);
      url = base + "/models/" + model.replaceFirst("^models/", "") + ":generateContent";
    } else {
      body =
          Map.of(
              "model",
              model,
              "messages",
              List.of(
                  Map.of("role", "system", "content", ContextBuilder.INSTRUCTION),
                  Map.of("role", "user", "content", context)),
              "max_tokens",
              settings.outputTokens,
              "temperature",
              0.65);
      url = base + "/chat/completions";
    }
    var result =
        JsonParser.parseString(
                http.post(url, apiKey, google, json.toJson(body), settings.timeoutSeconds))
            .getAsJsonObject();
    if (!google)
      return result
          .getAsJsonArray("choices")
          .get(0)
          .getAsJsonObject()
          .getAsJsonObject("message")
          .get("content")
          .getAsString();
    var parts =
        result
            .getAsJsonArray("candidates")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("content")
            .getAsJsonArray("parts");
    var text = new StringBuilder();
    for (var part : parts) {
      var o = part.getAsJsonObject();
      if (o.has("text") && (!o.has("thought") || !o.get("thought").getAsBoolean()))
        text.append(o.get("text").getAsString());
    }
    return text.toString();
  }
}
