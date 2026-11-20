package dev.qb2.client.config;

public final class Settings {
  public String provider = "google";
  public String endpoint = "https://generativelanguage.googleapis.com/v1beta";
  public String model = "gemma-4-31b-it";
  public String nerveEndpoint = "http://127.0.0.1:17842";
  public int memoryTopK = 3;
  public double perceptionRadius = 10, exploreRadius = 7, fieldOfView = 110;
  public int inferenceSeconds = 18, tokenBudget = 2200, outputTokens = 350, timeoutSeconds = 25;
  public boolean autonomous = true, thoughts = true, speech = true, particles = true;
  public boolean debug = false, paused = false, serverChat = false;
  public double glow = 0.85, bubbleOpacity = 0.8;
  public String triggers = "@cube,@qb,qb2";

  public void validate() {
    memoryTopK = Math.clamp(memoryTopK, 1, 20);
    perceptionRadius = finite(perceptionRadius, 3, 24);
    exploreRadius = finite(exploreRadius, 2, 20);
    fieldOfView = finite(fieldOfView, 30, 180);
    inferenceSeconds = Math.clamp(inferenceSeconds, 5, 300);
    tokenBudget = Math.clamp(tokenBudget, 800, 12000);
    outputTokens = Math.clamp(outputTokens, 100, 1200);
    timeoutSeconds = Math.clamp(timeoutSeconds, 2, 60);
    glow = finite(glow, 0, 1);
    bubbleOpacity = finite(bubbleOpacity, 0.1, 1);
    if (!provider.equals("google") && !provider.equals("openai"))
      throw new IllegalArgumentException("Provider: google/openai");
    if (!model.matches("[a-zA-Z0-9._:/-]{1,150}"))
      throw new IllegalArgumentException("Invalid model ID");
    dev.qb2.inference.HttpTransport.validateEndpoint(endpoint);
    dev.qb2.inference.HttpTransport.validateEndpoint(nerveEndpoint);
  }

  private static double finite(double value, double min, double max) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Finite value required");
    return Math.clamp(value, min, max);
  }
}
