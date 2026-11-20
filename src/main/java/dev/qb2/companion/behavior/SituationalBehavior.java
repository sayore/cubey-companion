package dev.qb2.companion.behavior;

import dev.qb2.companion.movement.Point;
import dev.qb2.perception.world.WorldSensor;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Purely client-local situational micro-behaviors (zero LLM tokens) that make QB2 feel alive in
 * response to eye contact, sneaking, crafting, rain, nearby threats, darkness, item drops, warmth,
 * player damage, and level-ups/music.
 */
public final class SituationalBehavior {
  public boolean sneaking;
  public boolean peeking;
  public boolean darkMode;
  public boolean rainSeeking;
  public boolean warmGlow;
  public boolean dancing;
  public int greetingTicks;
  public int threatTicks;
  public int itemDropTicks;
  public int concernTicks;
  public int celebrateTicks;
  public int rainShiverTicks;
  public Point focusLookOverride;
  public boolean wantForwardFov;
  public boolean triggerGreetingSound;
  public boolean triggerCelebrateSound;

  private int eyeContactTicks;
  private long lastGreetingTick = -1000;
  private int lastXpLevel = -1;
  private final Set<Integer> seenDropIds = new HashSet<>();

  public void tick(Minecraft mc, Point cubePos, long tick, boolean perched) {
    triggerGreetingSound = false;
    triggerCelebrateSound = false;
    if (mc == null || mc.level == null || mc.player == null) return;

    if (greetingTicks > 0) greetingTicks--;
    if (threatTicks > 0) threatTicks--;
    if (itemDropTicks > 0) itemDropTicks--;
    if (concernTicks > 0) concernTicks--;
    if (celebrateTicks > 0) celebrateTicks--;
    if (rainShiverTicks > 0) rainShiverTicks--;

    var player = mc.player;
    var level = mc.level;

    // 2. Sneak sync
    sneaking = player.isCrouching();

    // 3. Over-the-shoulder peeking when container/workbench/machine UI is open
    peeking = mc.screen != null && !(mc.screen instanceof dev.qb2.ui.screens.SettingsScreen);

    // 9. Reaction to player taking damage
    if (player.hurtTime > 0 && concernTicks < 35) {
      concernTicks = 55;
    }

    // 10a. Level-up celebration spin
    int xp = player.experienceLevel;
    if (lastXpLevel >= 0 && xp > lastXpLevel) {
      celebrateTicks = 45;
      triggerCelebrateSound = true;
    }
    lastXpLevel = xp;

    // 1. Eye-contact greeting (~0.8s gaze within ~12 deg)
    Vec3 eye = player.getEyePosition();
    Vec3 toCube = WorldSensor.vec(cubePos).subtract(eye);
    double dist = toCube.length();
    if (dist > 0.8 && dist < 8.0) {
      double dot = player.getLookAngle().normalize().dot(toCube.normalize());
      if (dot > 0.975) {
        eyeContactTicks++;
        if (eyeContactTicks >= 16 && tick - lastGreetingTick > 200) {
          greetingTicks = 32;
          lastGreetingTick = tick;
          triggerGreetingSound = true;
        }
      } else {
        eyeContactTicks = Math.max(0, eyeContactTicks - 2);
      }
    } else {
      eyeContactTicks = 0;
    }

    // Periodic environment checks (every 10 ticks = 0.5s, super cheap)
    if (tick % 10 == 0) {
      BlockPos playerBp = player.blockPosition();
      BlockPos cubeBp = BlockPos.containing(cubePos.x(), cubePos.y(), cubePos.z());

      // 6. Cave / darkness companion
      darkMode = level.hasChunkAt(playerBp) && level.getMaxLocalRawBrightness(playerBp) < 5;

      // 4. Rain shyness
      boolean rainingOnCube =
          level.isRaining() && level.hasChunkAt(cubeBp) && level.canSeeSky(cubeBp);
      if (rainingOnCube && !rainSeeking) rainShiverTicks = 20;
      rainSeeking = rainingOnCube;

      // 5. Threat reflex on nearby hostile mobs (< 6.5 blocks)
      var mobs =
          level.getEntitiesOfClass(
              Monster.class, new AABB(player.position(), player.position()).inflate(6.5));
      if (!mobs.isEmpty()) {
        Monster nearest = mobs.get(0);
        double bestD = nearest.distanceToSqr(player);
        for (int i = 1; i < mobs.size(); i++) {
          double d = mobs.get(i).distanceToSqr(player);
          if (d < bestD) {
            bestD = d;
            nearest = mobs.get(i);
          }
        }
        focusLookOverride = WorldSensor.point(nearest.getEyePosition());
        threatTicks = 35;
      } else if (threatTicks == 0) {
        // 7. Curiosity toward fresh item drops (< 5.5 blocks)
        var drops =
            level.getEntitiesOfClass(
                ItemEntity.class, new AABB(player.position(), player.position()).inflate(5.5));
        for (ItemEntity drop : drops) {
          if (seenDropIds.add(drop.getId())) {
            focusLookOverride = WorldSensor.point(drop.position().add(0, 0.2, 0));
            itemDropTicks = 45;
            break;
          }
        }
        if (seenDropIds.size() > 64) seenDropIds.clear();
      }

      // 8. Warmth near lit furnaces/campfires & 10b. Playing jukebox
      warmGlow = false;
      dancing = false;
      for (int dx = -3; dx <= 3; dx++) {
        for (int dy = -1; dy <= 2; dy++) {
          for (int dz = -3; dz <= 3; dz++) {
            BlockPos bp = cubeBp.offset(dx, dy, dz);
            if (!level.hasChunkAt(bp)) continue;
            var st = level.getBlockState(bp);
            if ((st.is(Blocks.CAMPFIRE)
                    || st.is(Blocks.SOUL_CAMPFIRE)
                    || st.is(Blocks.FURNACE)
                    || st.is(Blocks.BLAST_FURNACE)
                    || st.is(Blocks.SMOKER))
                && st.hasProperty(BlockStateProperties.LIT)
                && st.getValue(BlockStateProperties.LIT)) {
              warmGlow = true;
            }
            if (st.is(Blocks.JUKEBOX)
                && level.getBlockEntity(bp) instanceof JukeboxBlockEntity jb
                && !jb.getTheItem().isEmpty()) {
              dancing = true;
            }
          }
        }
      }
    }

    if (threatTicks == 0 && itemDropTicks == 0) focusLookOverride = null;
    wantForwardFov =
        !perched
            && (peeking
                || concernTicks > 0
                || celebrateTicks > 0
                || greetingTicks > 0
                || darkMode);
  }
}
