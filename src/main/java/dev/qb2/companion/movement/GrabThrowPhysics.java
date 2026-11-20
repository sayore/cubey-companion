package dev.qb2.companion.movement;

import java.util.function.Predicate;

/**
 * Pure-Java physics controller for grabbing Cubey (hold LMB), charging a throw (hold RMB while
 * holding LMB), releasing gently, and ballistic flight with elastic block bouncing.
 */
public final class GrabThrowPhysics {
  public enum Mode {
    NONE,
    GRABBED,
    THROWN
  }

  public static final int MAX_CHARGE_TICKS = 40; // 2.0 seconds for full throw power
  public static final int MAX_FLIGHT_TICKS = 60;
  public static final int MAX_BOUNCES = 3;

  private Mode mode = Mode.NONE;
  private int chargeTicks;
  private boolean charging;
  private Point velocity = Point.ZERO;
  private int flightTicks;
  private int bounces;
  private boolean justLaunched;
  private boolean justBounced;
  private float lastLaunchCharge;

  public Mode mode() {
    return mode;
  }

  public boolean isGrabbed() {
    return mode == Mode.GRABBED;
  }

  public boolean isThrown() {
    return mode == Mode.THROWN;
  }

  public boolean isCharging() {
    return mode == Mode.GRABBED && charging;
  }

  public float chargeRatio() {
    return Math.clamp(chargeTicks / (float) MAX_CHARGE_TICKS, 0f, 1f);
  }

  public float lastLaunchCharge() {
    return lastLaunchCharge;
  }

  public Point velocity() {
    return velocity;
  }

  public boolean consumeJustLaunched() {
    boolean v = justLaunched;
    justLaunched = false;
    return v;
  }

  public boolean consumeJustBounced() {
    boolean v = justBounced;
    justBounced = false;
    return v;
  }

  public void startGrab() {
    mode = Mode.GRABBED;
    chargeTicks = 0;
    charging = false;
    velocity = Point.ZERO;
    flightTicks = 0;
    bounces = 0;
  }

  public void release() {
    mode = Mode.NONE;
    chargeTicks = 0;
    charging = false;
    velocity = Point.ZERO;
  }

  /**
   * Updates grab/charge state each tick. Returns the desired hold position while grabbed, or null
   * if released or launched into THROWN mode.
   */
  public Point tickGrab(
      Point playerEyePos, Point lookUnit, boolean lmbDown, boolean rmbDown) {
    if (mode != Mode.GRABBED) return null;

    if (lmbDown && rmbDown) {
      charging = true;
      if (chargeTicks < MAX_CHARGE_TICKS) chargeTicks++;
    } else if (charging && (!rmbDown || !lmbDown)) {
      // Released RMB while holding LMB (or released both after charging) -> LAUNCH!
      launch(lookUnit);
      return null;
    } else if (!lmbDown) {
      // Released LMB without charging -> gentle placement!
      release();
      return null;
    }

    float charge = chargeRatio();
    // Pull closer toward player as throw charges like a coiled spring!
    double holdDist = 1.48 - charge * 0.52;
    return playerEyePos.add(lookUnit.scale(holdDist)).add(new Point(0, -0.14 + charge * 0.08, 0));
  }

  public void launch(Point lookUnit) {
    float charge = chargeRatio();
    lastLaunchCharge = charge;
    double speed = 0.42 + charge * 1.68; // 0.42 (soft toss) .. 2.10 blocks/tick (full yeet!)
    double loft = 0.10 + charge * 0.14;
    Point dir = lookUnit.length() > 0.05 ? lookUnit.unit() : new Point(0, 0, 1);
    velocity = new Point(dir.x() * speed, dir.y() * speed + loft, dir.z() * speed);
    mode = Mode.THROWN;
    chargeTicks = 0;
    charging = false;
    flightTicks = 0;
    bounces = 0;
    justLaunched = true;
  }

  /**
   * Advances ballistic throw physics by one tick with gravity, air drag, and per-axis elastic
   * bouncing against solid blocks. Returns the new position.
   */
  public Point tickThrown(Point currentPos, Predicate<Point> free) {
    if (mode != Mode.THROWN) return currentPos;
    flightTicks++;

    // Gravity + subtle air drag
    double vx = velocity.x() * 0.985;
    double vy = (velocity.y() - 0.052) * 0.985;
    double vz = velocity.z() * 0.985;

    // Sub-step along high velocities so Cubey never tunnels through thin walls!
    double stepSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);
    int steps = Math.max(1, (int) Math.ceil(stepSpeed / 0.25));
    double px = currentPos.x(), py = currentPos.y(), pz = currentPos.z();
    double svx = vx / steps, svy = vy / steps, svz = vz / steps;
    boolean bouncedThisTick = false;

    for (int s = 0; s < steps; s++) {
      if (free.test(new Point(px + svx, py, pz))) {
        px += svx;
      } else {
        vx = -vx * 0.58;
        svx = -svx * 0.58;
        bouncedThisTick = true;
      }
      if (free.test(new Point(px, py + svy, pz))) {
        py += svy;
      } else {
        vy = -vy * 0.58;
        svy = -svy * 0.58;
        vx *= 0.82;
        vz *= 0.82;
        svx *= 0.82;
        svz *= 0.82;
        bouncedThisTick = true;
      }
      if (free.test(new Point(px, py, pz + svz))) {
        pz += svz;
      } else {
        vz = -vz * 0.58;
        svz = -svz * 0.58;
        bouncedThisTick = true;
      }
    }

    if (bouncedThisTick) {
      bounces++;
      justBounced = true;
    }

    velocity = new Point(vx, vy, vz);
    if (flightTicks >= MAX_FLIGHT_TICKS
        || bounces >= MAX_BOUNCES
        || (bounces >= 1 && velocity.length() < 0.09)) {
      mode = Mode.NONE;
      velocity = Point.ZERO;
    }
    return new Point(px, py, pz);
  }

  public static String formatChargeBar(float charge) {
    int filled = Math.round(Math.clamp(charge, 0f, 1f) * 6);
    StringBuilder sb = new StringBuilder("⚡ [");
    for (int i = 0; i < 6; i++) sb.append(i < filled ? '█' : '░');
    sb.append(']');
    return sb.toString();
  }
}
