package dev.qb2.companion.behavior;

import dev.qb2.companion.movement.Point;
import dev.qb2.perception.world.WorldSensor;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SpyglassItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Purely client-local situational micro-behaviors (20 behaviors + pet riding/playing + dynamic hats
 * + rare character easter eggs, 0 LLM tokens).
 */
public final class SituationalBehavior {
  public boolean sneaking;
  public boolean peeking;
  public boolean darkMode;
  public boolean rainSeeking;
  public boolean warmGlow;
  public boolean dancing;
  public boolean sleepingWithPlayer;
  public boolean aimingSpotter;
  public boolean wingmanMode;
  public boolean eatingWithPlayer;
  public boolean sunsetGlow;
  public boolean portalResonance;
  public boolean fishingWatch;
  public boolean fishingBiteHop;

  public int greetingTicks;
  public int threatTicks;
  public int itemDropTicks;
  public int concernTicks;
  public int celebrateTicks;
  public int rainShiverTicks;
  public int abyssLookTicks;
  public int oreSparkleTicks;
  public int lightningStartleTicks;
  public int barrelRollTicks;
  public int petPlayTicks;
  public int workClearanceTicks;
  public int combatTicks;

  public Point focusLookOverride;
  public Point specialGoalOverride;
  public Entity perchedEntity;
  public boolean wantForwardFov;
  public boolean triggerGreetingSound;
  public boolean triggerCelebrateSound;
  public String pendingDurabilityWarning = "";
  public float wingmanRoll;
  public String forcedHat = "auto";
  public String activeHat = "none";

  public final SocialChoreography social = new SocialChoreography();
  public float socialYaw;
  public float socialPitch;
  public float socialRoll;
  public double socialBob;
  public Point socialLinkTarget;
  private final java.util.Map<java.util.UUID, Boolean> otherPlayerCrouching = new java.util.HashMap<>();

  private int eyeContactTicks;
  private long lastGreetingTick = -1000;
  private int lastXpLevel = -1;
  private float lastPlayerYaw;
  private final Set<Integer> seenDropIds = new HashSet<>();
  private final Set<Integer> seenLightningIds = new HashSet<>();
  private final java.util.Map<String, Long> durabilityWarnedAt = new java.util.HashMap<>();

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
    if (abyssLookTicks > 0) abyssLookTicks--;
    if (oreSparkleTicks > 0) oreSparkleTicks--;
    if (lightningStartleTicks > 0) lightningStartleTicks--;
    if (barrelRollTicks > 0) barrelRollTicks--;
    if (petPlayTicks > 0) petPlayTicks--;
    if (workClearanceTicks > 0) workClearanceTicks--;
    if (combatTicks > 0) combatTicks--;
    social.tickDown();
    socialYaw = 0f;
    socialPitch = 0f;
    socialRoll = 0f;
    socialBob = 0.0;
    socialLinkTarget = null;

    var player = mc.player;
    var level = mc.level;
    if (player.swinging) {
      if (isWeapon(player.getMainHandItem())) {
        combatTicks = Math.max(combatTicks, 120);
      } else if (workClearanceTicks < 50) {
        workClearanceTicks = 65;
      }
    }
    if (player.hurtTime > 0) {
      combatTicks = Math.max(combatTicks, 140);
    }
    specialGoalOverride = null;

    // Validate perched entity (pet/animal riding)
    if (perchedEntity != null) {
      if (!perchedEntity.isAlive()
          || perchedEntity.distanceTo(player) > 14.0
          || player.hurtTime > 0
          || threatTicks > 0) {
        perchedEntity = null;
      } else {
        var box = perchedEntity.getBoundingBox();
        specialGoalOverride =
            new Point(perchedEntity.getX(), box.maxY + 0.13, perchedEntity.getZ());
      }
    }

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

    // 11. Bed / sleep companion
    sleepingWithPlayer = player.isSleeping();
    if (sleepingWithPlayer) {
      Vec3 bedFoot = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(0.8));
      specialGoalOverride = new Point(bedFoot.x, player.getY() + 0.72, bedFoot.z);
    }

    // 12. Fishing bobber spectator & bite hop
    var hook = player.fishing;
    fishingWatch = hook != null && hook.isAlive();
    fishingBiteHop = false;
    if (fishingWatch) {
      Vec3 hPos = hook.position();
      specialGoalOverride = new Point(hPos.x + 0.55, hPos.y + 0.75, hPos.z + 0.55);
      focusLookOverride = new Point(hPos.x, hPos.y, hPos.z);
      if (hook.getDeltaMovement().y < -0.08) {
        fishingBiteHop = true;
      }
    }

    // 13. Aiming spotter (Bow / Crossbow / Spyglass) & 19. Eating/Drinking companion
    var activeItem = player.getUseItem();
    boolean using = player.isUsingItem() && !activeItem.isEmpty();
    aimingSpotter =
        using
            && (activeItem.getItem() instanceof BowItem
                || activeItem.getItem() instanceof CrossbowItem
                || activeItem.getItem() instanceof SpyglassItem);
    eatingWithPlayer =
        using
            && (activeItem.getUseAnimation() == UseAnim.EAT
                || activeItem.getUseAnimation() == UseAnim.DRINK);
    if (aimingSpotter) {
      Vec3 forward = player.getLookAngle().normalize();
      Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
      Vec3 spotter = player.getEyePosition().add(right.scale(0.75)).add(forward.scale(0.4)).add(0, 0.25, 0);
      specialGoalOverride = WorldSensor.point(spotter);
      focusLookOverride = WorldSensor.point(player.getEyePosition().add(forward.scale(16.0)));
    }

    // 15. Wingman formation (Elytra / Boat / Minecart / Immersive Aircraft / any mounted vehicle)
    Entity vehicle = player.getVehicle();
    wingmanMode = player.isFallFlying() || player.isPassenger() || vehicle != null;
    float yawDelta = (player.getYRot() - lastPlayerYaw) % 360f;
    if (yawDelta > 180f) yawDelta -= 360f;
    if (yawDelta < -180f) yawDelta += 360f;
    lastPlayerYaw = player.getYRot();
    wingmanRoll = wingmanMode ? Math.clamp(-yawDelta * 2.8f, -38f, 38f) : 0f;
    if (wingmanMode) {
      perchedEntity = null;
      Vec3 vForward =
          (vehicle != null && vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.005)
              ? vehicle.getDeltaMovement().normalize()
              : player.getLookAngle().normalize();
      Vec3 horizRight = new Vec3(vForward.x, 0, vForward.z).normalize().cross(new Vec3(0, 1, 0)).normalize();
      if (horizRight.lengthSqr() < 0.01) horizRight = new Vec3(1, 0, 0);
      Vec3 wingPos =
          player
              .getEyePosition()
              .add(horizRight.scale(1.85))
              .add(vForward.scale(1.15))
              .add(0, 0.45, 0);
      specialGoalOverride = WorldSensor.point(wingPos);
      focusLookOverride = WorldSensor.point(wingPos.add(vForward.scale(12.0)));
      if (barrelRollTicks == 0 && tick % 180 == 0) {
        barrelRollTicks = 28;
      }
    }

    // When sleeping/perched, stay strictly powered down: skip eye-contact nod and active distractions.
    if (perched || sleepingWithPlayer) {
      greetingTicks = 0;
      itemDropTicks = 0;
      abyssLookTicks = 0;
      oreSparkleTicks = 0;
      petPlayTicks = 0;
      social.cancel();
      peeking = false;
      wantForwardFov = false;
      activeHat = resolveHat(player, true);
      return;
    }

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

    // Periodic environment checks (every 10 ticks = 0.5s)
    if (tick % 10 == 0) {
      BlockPos playerBp = player.blockPosition();
      BlockPos cubeBp = BlockPos.containing(cubePos.x(), cubePos.y(), cubePos.z());

      // 6. Cave / darkness companion
      darkMode = level.hasChunkAt(playerBp) && level.getMaxLocalRawBrightness(playerBp) < 5;

      // Tool / armor 5% durability saver warning (local, 0 LLM tokens)
      checkDurability(player, tick);

      // 4. Rain shyness
      boolean rainingOnCube =
          level.isRaining() && level.hasChunkAt(cubeBp) && level.canSeeSky(cubeBp);
      if (rainingOnCube && !rainSeeking) rainShiverTicks = 20;
      rainSeeking = rainingOnCube;

      // 20. Sunrise / Sunset horizon moment
      long dayTime = level.getDayTime() % 24000L;
      boolean goldenHour =
          (dayTime >= 22800L || dayTime <= 800L) || (dayTime >= 11600L && dayTime <= 13200L);
      sunsetGlow = goldenHour && level.hasChunkAt(playerBp) && level.canSeeSky(playerBp);

      // 18. Thunder / Lightning startle reflex
      if (level.isThundering()) {
        var bolts =
            level.getEntitiesOfClass(
                LightningBolt.class, new AABB(player.position(), player.position()).inflate(48.0));
        for (LightningBolt bolt : bolts) {
          if (seenLightningIds.add(bolt.getId())) {
            lightningStartleTicks = 45;
            perchedEntity = null;
            break;
          }
        }
        if (seenLightningIds.size() > 32) seenLightningIds.clear();
      }

      // 14. Abyss / Cliff peer down (> 9 blocks drop in front of player)
      if (!wingmanMode && !perched && abyssLookTicks == 0 && tick % 60 == 0) {
        Vec3 front = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(2.0));
        BlockPos edgeBp = BlockPos.containing(front.x, player.getY(), front.z);
        if (level.hasChunkAt(edgeBp)) {
          int airDrop = 0;
          for (int dy = 0; dy <= 10; dy++) {
            if (level.getBlockState(edgeBp.below(dy)).isAir()) airDrop++;
            else break;
          }
          if (airDrop >= 9) {
            abyssLookTicks = 45;
            focusLookOverride = new Point(front.x, player.getY() - 8.0, front.z);
          }
        }
      }

      // 5. Threat reflex on nearby hostile mobs (< 8.5 blocks) -> triggers safe combat overwatch!
      var mobs =
          level.getEntitiesOfClass(
              Monster.class, new AABB(player.position(), player.position()).inflate(8.5));
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
        combatTicks = Math.max(combatTicks, 100);
        portalResonance = nearest instanceof EnderMan;
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

        // 16. Pet & Villager playing / riding (< 5.5 blocks)
        if (perchedEntity == null && specialGoalOverride == null && !wingmanMode) {
          var friends =
              level.getEntities(
                  player,
                  new AABB(player.position(), player.position()).inflate(5.5),
                  e ->
                      e instanceof TamableAnimal
                          || e instanceof Animal
                          || e instanceof AbstractVillager);
          if (!friends.isEmpty()) {
            Entity friend = friends.get(0);
            if (tick % 160 < 80 && friend instanceof Animal) {
              // Sit on the pet/animal's head/back!
              perchedEntity = friend;
            } else {
              // Playfully orbit/look at the pet or villager!
              petPlayTicks = 35;
              double ang = tick * 0.14;
              var fb = friend.getBoundingBox();
              specialGoalOverride =
                  new Point(
                      friend.getX() + Math.cos(ang) * 1.15,
                      fb.maxY + 0.35 + Math.abs(Math.sin(ang * 2)) * 0.2,
                      friend.getZ() + Math.sin(ang) * 1.15);
              focusLookOverride = WorldSensor.point(friend.getEyePosition());
            }
          }
        }
      }

      // 8. Warmth, 10b. Playing jukebox, 17. Ore sparkle, & Portal resonance
      warmGlow = false;
      dancing = false;
      boolean holdingPickaxe = player.getMainHandItem().getItem() instanceof PickaxeItem;
      if (threatTicks == 0) portalResonance = false;
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
            if (st.is(Blocks.NETHER_PORTAL) || st.is(Blocks.END_PORTAL)) {
              portalResonance = true;
            }
            if (holdingPickaxe && oreSparkleTicks == 0) {
              String bid = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
              if (bid.endsWith("_ore") || bid.equals("ancient_debris")) {
                oreSparkleTicks = 35;
                focusLookOverride = new Point(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
              }
            }
          }
        }
      }
    }

    if (threatTicks == 0
        && itemDropTicks == 0
        && abyssLookTicks == 0
        && oreSparkleTicks == 0
        && petPlayTicks == 0
        && !social.isActive()
        && !fishingWatch
        && !aimingSpotter) {
      focusLookOverride = null;
    }

    wantForwardFov =
        !perched
            && perchedEntity == null
            && !social.isActive()
            && combatTicks == 0
            && workClearanceTicks == 0
            && (peeking
                || celebrateTicks > 0
                || greetingTicks > 0
                || darkMode
                || eatingWithPlayer
                || lightningStartleTicks > 0
                || wingmanMode);

    activeHat = resolveHat(player, perched);
  }

  public static boolean isWeapon(net.minecraft.world.item.ItemStack stack) {
    if (stack == null || stack.isEmpty()) return false;
    var item = stack.getItem();
    if (item instanceof net.minecraft.world.item.SwordItem
        || item instanceof net.minecraft.world.item.AxeItem
        || item instanceof net.minecraft.world.item.TridentItem
        || item instanceof net.minecraft.world.item.MaceItem
        || item instanceof BowItem
        || item instanceof CrossbowItem) return true;
    String id = BuiltInRegistries.ITEM.getKey(item).getPath();
    return id.contains("sword")
        || id.contains("blade")
        || id.contains("katana")
        || id.contains("spear")
        || id.contains("scythe")
        || id.contains("claymore")
        || id.contains("staff")
        || id.contains("wand")
        || id.contains("spell");
  }

  public void tickSocialInteractions(
      Minecraft mc,
      Point cubePos,
      long tick,
      dev.qb2.network.RemoteCompanionTracker peers,
      dev.qb2.rendering.bubbles.Bubbles bubbles) {
    if (mc == null || mc.level == null || mc.player == null) return;
    if (wingmanMode || threatTicks > 0 || concernTicks > 0 || sleepingWithPlayer) {
      social.cancel();
      return;
    }
    var me = mc.player;

    // 1. Check nearby human players for reactive crouch-greetings or rare spontaneous social emotes
    net.minecraft.world.entity.player.Player nearestOtherPlayer = null;
    double nearestPlayerDist = 6.0;
    boolean crouchToggleDetected = false;
    for (var other : mc.level.players()) {
      if (other == me || !other.isAlive() || other.isSpectator() || other.isInvisible()) continue;
      double d = other.distanceTo(me);
      if (d <= 6.0) {
        boolean wasCrouching = otherPlayerCrouching.getOrDefault(other.getUUID(), false);
        boolean nowCrouching = other.isCrouching();
        otherPlayerCrouching.put(other.getUUID(), nowCrouching);
        if (nowCrouching && !wasCrouching) {
          crouchToggleDetected = true;
          nearestOtherPlayer = other;
          nearestPlayerDist = d;
          break;
        }
        if (d < nearestPlayerDist) {
          nearestPlayerDist = d;
          nearestOtherPlayer = other;
        }
      }
    }
    if (otherPlayerCrouching.size() > 32) otherPlayerCrouching.clear();

    // 2. Try starting a rare social emote if none is active
    if (!social.isActive() && (tick % 20 == 0 || crouchToggleDetected)) {
      var nearPeer = peers.nearestPeer(cubePos, 5.5);
      if (nearPeer.isPresent() && !crouchToggleDetected) {
        var peer = nearPeer.get();
        double d = peer.pos.distance(cubePos);
        if (!social.tryStartPeerCubeyEmote(
            me.getUUID(), peer.ownerId, peer.ownerName, peer.hat, tick, d)) {
          // Subtle quick greeting nod if within 3.5m and no full choreography triggered
          if (d <= 3.5 && greetingTicks == 0 && tick - lastGreetingTick > 240) {
            greetingTicks = 26;
            lastGreetingTick = tick;
            focusLookOverride = peer.pos;
          }
        }
      }
      if (!social.isActive() && nearestOtherPlayer != null) {
        boolean stationary = nearestOtherPlayer.getDeltaMovement().horizontalDistanceSqr() < 0.004;
        social.tryStartOtherPlayerEmote(
            nearestOtherPlayer.getUUID(),
            nearestOtherPlayer.getName().getString(),
            crouchToggleDetected,
            stationary,
            tick,
            nearestPlayerDist);
      }
    }

    // 3. Drive active social choreography pose & optional temporary hat/bubble
    if (social.isActive()) {
      String bubble = social.consumePendingBubble();
      if (!bubble.isBlank() && bubbles != null) {
        bubbles.speech(bubble, tick);
      }
      if (social.consumeTriggerSound()) {
        triggerGreetingSound = true;
      }

      Point anchor = null;
      Point anchorLook = null;
      java.util.UUID tid = social.targetId();
      if (tid != null) {
        for (var peer : peers.activePeers()) {
          if (tid.equals(peer.ownerId)) {
            anchor = peer.pos;
            break;
          }
        }
        if (anchor == null) {
          for (var other : mc.level.players()) {
            if (tid.equals(other.getUUID()) && other.isAlive() && other.distanceTo(me) <= 9.0) {
              anchor = WorldSensor.point(other.getEyePosition());
              anchorLook = WorldSensor.point(other.getLookAngle());
              break;
            }
          }
        }
      }
      if (anchor == null) {
        social.cancel();
        return;
      }
      var pose = social.computePose(me.getUUID(), cubePos, anchor, anchorLook, 0f);
      if (pose != null) {
        specialGoalOverride = pose.goalPosition();
        focusLookOverride = pose.lookTarget();
        socialYaw = pose.extraYaw();
        socialPitch = pose.extraPitch();
        socialRoll = pose.extraRoll();
        socialBob = pose.extraBob();
        socialLinkTarget = pose.showResonanceLink() ? anchor : null;
        if (pose.temporaryHat() != null && "auto".equals(forcedHat)) {
          activeHat = pose.temporaryHat();
        }
      }
    }
  }

  public void markWorking() {
    workClearanceTicks = 75;
  }

  private void checkDurability(net.minecraft.client.player.LocalPlayer player, long tick) {
    var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>(6);
    items.add(player.getMainHandItem());
    items.add(player.getOffhandItem());
    player.getArmorSlots().forEach(items::add);
    for (var stack : items) {
      if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) continue;
      int max = stack.getMaxDamage();
      if (max < 20) continue;
      int rem = max - stack.getDamageValue();
      int threshold = Math.max(3, max / 20);
      if (rem <= threshold) {
        String key = stack.getHoverName().getString();
        Long last = durabilityWarnedAt.get(key);
        if (last == null || tick - last > 1200) {
          durabilityWarnedAt.put(key, tick);
          pendingDurabilityWarning = "⚠ " + key + ": only " + rem + " durability left!";
          break;
        }
      }
    }
  }

  private String resolveHat(net.minecraft.client.player.LocalPlayer player, boolean perched) {
    if (forcedHat != null && !forcedHat.equals("auto")) return forcedHat;
    if (celebrateTicks > 0) return "crown";
    if (sleepingWithPlayer || perched) return "sleep";
    if (rainSeeking) return "rain";
    if (wingmanMode) return "propeller";
    if (darkMode || oreSparkleTicks > 0 || player.getMainHandItem().getItem() instanceof PickaxeItem)
      return "miner";
    if (fishingWatch || eatingWithPlayer || warmGlow) return "chef";
    return "antenna";
  }

  public void triggerBarrelRoll() {
    barrelRollTicks = 28;
  }
}
