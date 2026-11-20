package dev.qb2.rendering.animation;

/** Frame-time smoothing shared by orientation and light; no expressions resembling a human face. */
public final class CubeAnimation {
  public record Frame(float yaw, float pitch, float roll, float light) {}

  private float yaw, pitch, thinking, speaking, active;

  public Frame update(
      String state, double time, double speed, double dt, float lookYaw, float lookPitch) {
    float follow = (float) (1 - Math.exp(-Math.clamp(dt, 0, 0.1) * 6));
    float delta = (lookYaw - yaw) % 360;
    if (delta > 180) delta -= 360;
    if (delta < -180) delta += 360;
    yaw += delta * follow;
    pitch += (Math.clamp(lookPitch, -55, 55) - pitch) * follow;
    boolean isThinking = state.equals("Thinking");
    boolean isSpeaking = state.equals("Speaking");
    boolean isActive =
        isThinking || state.equals("Exploring") || state.equals("Curious") || state.equals("Returning");
    thinking += ((isThinking ? 1 : 0) - thinking) * follow;
    speaking += ((isSpeaking ? 1 : 0) - speaking) * follow;
    active += ((isActive ? 1 : 0) - active) * follow;

    // Lively multi-axis harmonic motion when actively thinking or investigating.
    float yawMotion =
        yaw + (float) (thinking * Math.sin(time * 2.2) * 14.0 + active * Math.sin(time * 1.1) * 3.0);
    float roll =
        (float)
            (Math.sin(time * 1.4) * (3.0 + active * 5.0)
                + thinking * Math.sin(time * 3.1) * 18.0);
    float tilt =
        pitch
            + (float)
                (Math.sin(time * 1.2) * (2.0 + active * 3.0)
                    + thinking * Math.cos(time * 2.5) * 14.0
                    - speed * 24.0);
    float light = (float) (0.80 + 0.10 * Math.sin(time * (2 + thinking * 4 + speaking * 9)));
    return new Frame(yawMotion, tilt, roll, light);
  }
}
