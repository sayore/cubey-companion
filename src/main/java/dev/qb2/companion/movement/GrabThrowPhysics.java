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

  public enum Surface {
    AIR,
    SOLID,
    WATER,
    LAVA,
    SLIME,
    SOFT
  }

  public enum ImpactEvent {
    NONE,
    BOUNCE,
    SPLASH,
    LAVA_NOPE,
    SLIME_BOING,
    SOFT_PLOP
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
  private ImpactEvent lastImpact = ImpactEvent.NONE;
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

  public ImpactEvent consumeLastImpact() {
    ImpactEvent ev = lastImpact;
    lastImpact = ImpactEvent.NONE;
    return ev;
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
   * bouncing against solid blocks (default convenience overload).
   */
  public Point tickThrown(Point currentPos, Predicate<Point> free) {
    return tickThrownSurface(currentPos, p -> free.test(p) ? Surface.AIR : Surface.SOLID);
  }

  /**
   * Material-aware ballistic throw physics:
   * - WATER: skips/skims across water surface with splash!
   * - LAVA: emergency upward boost & panic reverse away from lava!
   * - SLIME: 1.28x super-bounce!
   * - SOFT (wool/hay/bed): soft mochi cushion stop!
   */
  public Point tickThrownSurface(
      Point currentPos, java.util.function.Function<Point, Surface> surfaceAt) {
    if (mode != Mode.THROWN) return currentPos;
    flightTicks++;

    double vx = velocity.x() * 0.985;
    double vy = (velocity.y() - 0.052) * 0.985;
    double vz = velocity.z() * 0.985;

    // Check imminent lava directly underneath or ahead -> emergency boost up & away!
    Surface belowNow = surfaceAt.apply(new Point(currentPos.x(), currentPos.y() - 0.55, currentPos.z()));
    if (belowNow == Surface.LAVA) {
      vx = -vx * 0.65;
      vz = -vz * 0.65;
      vy = 0.52;
      bounces++;
      justBounced = true;
      lastImpact = ImpactEvent.LAVA_NOPE;
      velocity = new Point(vx, vy, vz);
      return currentPos.add(new Point(vx * 0.4, 0.35, vz * 0.4));
    }

    double stepSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);
    int steps = Math.max(1, (int) Math.ceil(stepSpeed / 0.25));
    double px = currentPos.x(), py = currentPos.y(), pz = currentPos.z();
    double svx = vx / steps, svy = vy / steps, svz = vz / steps;
    ImpactEvent impactThisTick = ImpactEvent.NONE;

    for (int s = 0; s < steps; s++) {
      Surface sx = surfaceAt.apply(new Point(px + svx, py, pz));
      if (sx == Surface.AIR) {
        px += svx;
      } else if (sx == Surface.LAVA) {
        vx = -vx * 0.7;
        vy = Math.max(vy, 0.48);
        svx = -svx * 0.7;
        impactThisTick = ImpactEvent.LAVA_NOPE;
      } else if (sx == Surface.WATER) {
        px += svx * 0.75;
        vy = Math.max(vy, 0.24);
        impactThisTick = ImpactEvent.SPLASH;
      } else {
        double rest = sx == Surface.SLIME ? 1.25 : sx == Surface.SOFT ? 0.12 : 0.58;
        vx = -vx * rest;
        svx = -svx * rest;
        impactThisTick =
            sx == Surface.SLIME
                ? ImpactEvent.SLIME_BOING
                : sx == Surface.SOFT ? ImpactEvent.SOFT_PLOP : ImpactEvent.BOUNCE;
      }

      Surface sy = surfaceAt.apply(new Point(px, py + svy, pz));
      if (sy == Surface.AIR) {
        py += svy;
      } else if (sy == Surface.LAVA) {
        vy = 0.52;
        vx = -vx * 0.6;
        vz = -vz * 0.6;
        svy = 0.1;
        impactThisTick = ImpactEvent.LAVA_NOPE;
      } else if (sy == Surface.WATER) {
        // Water skimming / stone-skipping!
        vy = Math.abs(vy) * 0.52 + 0.18;
        vx *= 0.86;
        vz *= 0.86;
        svy = vy / steps;
        impactThisTick = ImpactEvent.SPLASH;
      } else if (sy == Surface.SOFT) {
        // Soft landing on wool / hay / bed -> stop gently!
        vx *= 0.15;
        vy = 0;
        vz *= 0.15;
        bounces = MAX_BOUNCES;
        impactThisTick = ImpactEvent.SOFT_PLOP;
        break;
      } else {
        double rest = sy == Surface.SLIME ? 1.28 : 0.58;
        vy = -vy * rest;
        svy = -svy * rest;
        vx *= 0.82;
        vz *= 0.82;
        svx *= 0.82;
        svz *= 0.82;
        impactThisTick = sy == Surface.SLIME ? ImpactEvent.SLIME_BOING : ImpactEvent.BOUNCE;
      }

      Surface sz = surfaceAt.apply(new Point(px, py, pz + svz));
      if (sz == Surface.AIR) {
        pz += svz;
      } else if (sz == Surface.LAVA) {
        vz = -vz * 0.7;
        vy = Math.max(vy, 0.48);
        svz = -svz * 0.7;
        impactThisTick = ImpactEvent.LAVA_NOPE;
      } else if (sz == Surface.WATER) {
        pz += svz * 0.75;
        vy = Math.max(vy, 0.24);
        impactThisTick = ImpactEvent.SPLASH;
      } else {
        double rest = sz == Surface.SLIME ? 1.25 : sz == Surface.SOFT ? 0.12 : 0.58;
        vz = -vz * rest;
        svz = -svz * rest;
        impactThisTick =
            sz == Surface.SLIME
                ? ImpactEvent.SLIME_BOING
                : sz == Surface.SOFT ? ImpactEvent.SOFT_PLOP : ImpactEvent.BOUNCE;
      }
    }

    if (impactThisTick != ImpactEvent.NONE) {
      bounces++;
      justBounced = true;
      lastImpact = impactThisTick;
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
