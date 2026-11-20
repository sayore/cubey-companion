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
  private final Gson json = new Gson();

  public ApiLanguageModel(HttpTransport http, Settings settings, String key) {
    this.http = http;
    this.settings = settings;
    this.key = key;
  }

  public String complete(String context) throws Exception {
    if (key.isBlank()) throw new IllegalStateException("API key missing");
    String base = settings.endpoint.replaceAll("/+$", "");
    boolean google = settings.provider.equals("google");
    Object body;
    String url;
    if (google) {
      // Gemma 4 native API supports a separate system instruction.
      var generation = new LinkedHashMap<String, Object>();
      generation.put("maxOutputTokens", settings.outputTokens);
      generation.put("temperature", 0.65);
      if (settings.model.replaceFirst("^models/", "").startsWith("gemma-4-"))
        generation.put("thinkingConfig", Map.of("thinkingLevel", "minimal"));
      body =
          Map.of(
              "systemInstruction",
              Map.of("parts", List.of(Map.of("text", ContextBuilder.INSTRUCTION))),
              "contents",
              List.of(Map.of("role", "user", "parts", List.of(Map.of("text", context)))),
              "generationConfig",
              generation);
      url = base + "/models/" + settings.model.replaceFirst("^models/", "") + ":generateContent";
    } else {
      body =
          Map.of(
              "model",
              settings.model,
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
                http.post(url, key, google, json.toJson(body), settings.timeoutSeconds))
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
