package dev.qb2.client.config;

public final class Settings {
  public String provider = "google";
  public String endpoint = "https://generativelanguage.googleapis.com/v1beta";
  public String model = "gemma-4-31b-it";
  public String modelChain = "gemma-4-31b-it,gemma-4-26b-a4b-it,gemini-2.5-flash,gemini-2.5-flash-lite";
  public String ambientModel = "gemma-4-26b-a4b-it";
  public String fallbackProvider = "";
  public String fallbackEndpoint = "";
  public String fallbackModel = "";
  public String nerveEndpoint = "http://127.0.0.1:17842";
  public int memoryTopK = 3;
  public double perceptionRadius = 10, exploreRadius = 7, fieldOfView = 110;
  public int inferenceSeconds = 18, speechSeconds = 45, tokenBudget = 2200, outputTokens = 350, timeoutSeconds = 25;
  public boolean autonomous = true, thoughts = true, speech = true, particles = true;
  public boolean sounds = true, showTargetBeam = true, smoothFollow = true;
  public boolean debug = false, paused = false, serverChat = false;
  public double glow = 0.85, bubbleOpacity = 0.8;
  public String triggers = "@cube,@qb,qb2";
  public String style = "calm";

  public boolean hasFallback() {
    return fallbackModel != null && !fallbackModel.isBlank();
  }

  public java.util.List<String> parsedModelChain(boolean ambient) {
    var list = new java.util.ArrayList<String>();
    if (ambient && ambientModel != null && !ambientModel.isBlank()) list.add(ambientModel.trim());
    if (model != null && !model.isBlank()) list.add(model.trim());
    if (modelChain != null && !modelChain.isBlank()) {
      for (String part : modelChain.split(",")) {
        String trimmed = part.trim();
        if (!trimmed.isEmpty() && !list.contains(trimmed)) list.add(trimmed);
      }
    }
    return java.util.List.copyOf(list);
  }

  public Settings copy() {
    Settings c = new Settings();
    c.provider = provider;
    c.endpoint = endpoint;
    c.model = model;
    c.modelChain = modelChain;
    c.ambientModel = ambientModel;
    c.fallbackProvider = fallbackProvider;
    c.fallbackEndpoint = fallbackEndpoint;
    c.fallbackModel = fallbackModel;
    c.nerveEndpoint = nerveEndpoint;
    c.memoryTopK = memoryTopK;
    c.perceptionRadius = perceptionRadius;
    c.exploreRadius = exploreRadius;
    c.fieldOfView = fieldOfView;
    c.inferenceSeconds = inferenceSeconds;
    c.speechSeconds = speechSeconds;
    c.tokenBudget = tokenBudget;
    c.outputTokens = outputTokens;
    c.timeoutSeconds = timeoutSeconds;
    c.autonomous = autonomous;
    c.thoughts = thoughts;
    c.speech = speech;
    c.particles = particles;
    c.sounds = sounds;
    c.showTargetBeam = showTargetBeam;
    c.smoothFollow = smoothFollow;
    c.debug = debug;
    c.paused = paused;
    c.serverChat = serverChat;
    c.glow = glow;
    c.bubbleOpacity = bubbleOpacity;
    c.triggers = triggers;
    c.style = style;
    return c;
  }

  public void validate() {
    memoryTopK = Math.clamp(memoryTopK, 1, 20);
    perceptionRadius = finite(perceptionRadius, 3, 24);
    exploreRadius = finite(exploreRadius, 2, 20);
    fieldOfView = finite(fieldOfView, 30, 180);
    inferenceSeconds = Math.clamp(inferenceSeconds, 5, 300);
    speechSeconds = Math.clamp(speechSeconds, 10, 300);
    tokenBudget = Math.clamp(tokenBudget, 800, 12000);
    outputTokens = Math.clamp(outputTokens, 100, 1200);
    timeoutSeconds = Math.clamp(timeoutSeconds, 2, 60);
    glow = finite(glow, 0, 1);
    bubbleOpacity = finite(bubbleOpacity, 0.1, 1);
    if (modelChain == null) modelChain = "";
    if (ambientModel == null) ambientModel = "";
    if (fallbackProvider == null) fallbackProvider = "";
    if (fallbackEndpoint == null) fallbackEndpoint = "";
    if (fallbackModel == null) fallbackModel = "";
    if (style == null || style.isBlank()) style = "calm";
    if (style.length() > 120) style = style.substring(0, 120);
    if (!provider.equals("google") && !provider.equals("openai"))
      throw new IllegalArgumentException("Provider: google/openai");
    if (!model.matches("[a-zA-Z0-9._:/-]{1,150}"))
      throw new IllegalArgumentException("Invalid model ID");
    if (!ambientModel.isBlank() && !ambientModel.matches("[a-zA-Z0-9._:/-]{1,150}"))
      throw new IllegalArgumentException("Invalid ambient model ID");
    for (String part : modelChain.split(",")) {
      String m = part.trim();
      if (!m.isEmpty() && !m.matches("[a-zA-Z0-9._:/-]{1,150}"))
        throw new IllegalArgumentException("Invalid modelChain ID");
    }
    dev.qb2.inference.HttpTransport.validateEndpoint(endpoint);
    dev.qb2.inference.HttpTransport.validateEndpoint(nerveEndpoint);
    if (hasFallback()) {
      String fp = fallbackProvider.isBlank() ? provider : fallbackProvider;
      if (!fp.equals("google") && !fp.equals("openai"))
        throw new IllegalArgumentException("Fallback provider: google/openai");
      if (!fallbackModel.matches("[a-zA-Z0-9._:/-]{1,150}"))
        throw new IllegalArgumentException("Invalid fallback model ID");
      String fe = fallbackEndpoint.isBlank() ? endpoint : fallbackEndpoint;
      dev.qb2.inference.HttpTransport.validateEndpoint(fe);
    }
  }

  private static double finite(double value, double min, double max) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite value required");
    return Math.clamp(value, min, max);
  }
}
