package dev.qb2.rendering.animation;

/** Frame-time smoothing shared by orientation and light; no expressions resembling a human face. */
public final class CubeAnimation {
  public record Frame(float yaw, float pitch, float roll, float light) {}

  private float yaw, pitch, thinking, speaking;

  public Frame update(
      String state, double time, double speed, double dt, float lookYaw, float lookPitch) {
    float follow = (float) (1 - Math.exp(-Math.clamp(dt, 0, 0.1) * 6));
    float delta = (lookYaw - yaw) % 360;
    if (delta > 180) delta -= 360;
    if (delta < -180) delta += 360;
    yaw += delta * follow;
    pitch += (Math.clamp(lookPitch, -55, 55) - pitch) * follow;
    thinking += ((state.equals("Thinking") ? 1 : 0) - thinking) * follow;
    speaking += ((state.equals("Speaking") ? 1 : 0) - speaking) * follow;
    float roll = (float) (Math.sin(time * 1.4) * 3 + thinking * 4);
    float tilt = pitch + (float) (Math.sin(time) * 2 - speed * 22);
    float light = (float) (0.80 + 0.10 * Math.sin(time * (2 + thinking * 4 + speaking * 9)));
    return new Frame(yaw, tilt, roll, light);
  }
}
