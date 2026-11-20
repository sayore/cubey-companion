package dev.qb2.client.config;

public final class Settings {
  public String provider = "google";
  public String endpoint = "https://generativelanguage.googleapis.com/v1beta";
  public String model = "gemma-4-31b-it";
  public String modelChain = "gemma-4-31b-it,gemma-4-26b-a4b-it,gemini-2.5-flash-lite";
  public String ambientModel = "gemma-4-26b-a4b-it";
  public String fallbackProvider = "";
  public String fallbackEndpoint = "";
  public String fallbackModel = "";
  public String nerveEndpoint = "http://127.0.0.1:17842";
  public String whisperEndpoint = "http://127.0.0.1:17842/transcribe";
  public String whisperLanguage = "de";
  public int memoryTopK = 3;
  public double perceptionRadius = 10, exploreRadius = 7, fieldOfView = 110;
  public int inferenceSeconds = 20, speechSeconds = 45, tokenBudget = 950, outputTokens = 600, timeoutSeconds = 25;
  public boolean autonomous = true, thoughts = true, speech = true, particles = true;
  public boolean sounds = true, showTargetBeam = true, smoothFollow = true, multiplayerSync = true;
  public boolean radialMenu = true;
  public boolean debug = false, paused = false, serverChat = false;
  public double glow = 0.85, bubbleOpacity = 0.8, soundVolume = 0.75;
  public double voicePitch = 1.00, voiceTempo = 1.00, voiceVariance = 0.08;
  public String voiceMode = "animalese";
  public String cubeyTheme = "neo_cyan";
  public String cubeySkin = "obsidian";
  public String companionName = "Cubey";
  public String language = "de";
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
    c.whisperEndpoint = whisperEndpoint;
    c.whisperLanguage = whisperLanguage;
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
    c.multiplayerSync = multiplayerSync;
    c.radialMenu = radialMenu;
    c.debug = debug;
    c.paused = paused;
    c.serverChat = serverChat;
    c.glow = glow;
    c.bubbleOpacity = bubbleOpacity;
    c.soundVolume = soundVolume;
    c.voicePitch = voicePitch;
    c.voiceTempo = voiceTempo;
    c.voiceVariance = voiceVariance;
    c.voiceMode = voiceMode;
    c.cubeyTheme = cubeyTheme;
    c.cubeySkin = cubeySkin;
    c.companionName = companionName;
    c.language = language;
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
    soundVolume = finite(soundVolume, 0, 1);
    voicePitch = finite(voicePitch, 0.65, 1.55);
    voiceTempo = finite(voiceTempo, 0.60, 1.60);
    voiceVariance = finite(voiceVariance, 0.0, 0.25);
    if (!java.util.Set.of("silent", "chirp", "animalese").contains(voiceMode))
      voiceMode = "animalese";
    if (!java.util.Set.of("neo_cyan", "sakura", "amethyst", "solar", "emerald", "frost")
        .contains(cubeyTheme)) cubeyTheme = "neo_cyan";
    if (!java.util.Set.of("obsidian", "porcelain", "brass", "holo", "carbon", "pastel")
        .contains(cubeySkin)) cubeySkin = "obsidian";
    companionName = dev.qb2.network.CubeySyncValidator.sanitizeName(companionName);
    if (companionName.isBlank()) companionName = "Cubey";
    if (!java.util.Set.of("auto", "de", "en", "fr", "es", "ja").contains(language))
      language = "de";
    if (modelChain == null) modelChain = "";
    if (ambientModel == null) ambientModel = "";
    if (fallbackProvider == null) fallbackProvider = "";
    if (fallbackEndpoint == null) fallbackEndpoint = "";
    if (fallbackModel == null) fallbackModel = "";
    if (style == null || style.isBlank()) style = "calm";
    if (style.length() > 120) style = style.substring(0, 120);
    if (whisperEndpoint == null || whisperEndpoint.isBlank())
      whisperEndpoint = "http://127.0.0.1:17842/transcribe";
    if (whisperLanguage == null || whisperLanguage.isBlank()) whisperLanguage = "de";
    if (!whisperLanguage.matches("[a-z]{2,4}")) whisperLanguage = "auto";
    dev.qb2.inference.HttpTransport.validateEndpoint(whisperEndpoint);
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

  public int chatColorRgb() {
    return switch (cubeyTheme == null ? "neo_cyan" : cubeyTheme) {
      case "sakura" -> 0xFF88C8;
      case "amethyst" -> 0xC278FF;
      case "solar" -> 0xFFD055;
      case "emerald" -> 0x48F098;
      case "frost" -> 0x9EE6FF;
      default -> 0x48D6FF;
    };
  }

  public String languageLabel() {
    return switch (language == null ? "de" : language) {
      case "auto" -> "Auto";
      case "en" -> "English";
      case "fr" -> "Français";
      case "es" -> "Español";
      case "ja" -> "日本語";
      default -> "Deutsch";
    };
  }

  private static double finite(double value, double min, double max) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite value required");
    return Math.clamp(value, min, max);
  }
}
