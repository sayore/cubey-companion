package dev.qb2.rendering.bubbles;

/**
 * Caps the projected bubble footprint, independently of camera distance and window aspect ratio.
 */
public record BubbleLayout(float scale, int lineCount) {
  public static BubbleLayout calculate(
      double distance, double projectionX, double projectionY, int width, int lines) {
    int count = Math.clamp(lines, 1, 8);
    double x = Math.max(0.01, Math.abs(projectionX)), y = Math.max(0.01, Math.abs(projectionY));
    double horizontal = 0.42 * 2 * distance / (x * Math.max(1, width));
    double vertical = 0.30 * 2 * distance / (y * count * 10);
    return new BubbleLayout(
        (float) Math.max(0, Math.min(distance * 0.0055, Math.min(horizontal, vertical))), count);
  }
}
