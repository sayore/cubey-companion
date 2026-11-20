package dev.qb2.server;

import dev.qb2.network.CubeyNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.*;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

/**
 * Server-side automation & combat support for Cubey's 6-slot Mini-Backpack and dedicated Equipment
 * Slot. Runs on both integrated singleplayer and dedicated multiplayer servers without registering
 * any custom items, blocks, or entities.
 */
public final class CubeyServerHelper {
  private static final int CRAFT_DURATION_TICKS = 60; // 3.0s visual crafting choreography

  private record CraftRecipe(
      String id,
      boolean cookingStationOnly,
      boolean isFood,
      Map<Item, Integer> inputs,
      Item output,
      int outputCount) {}

  private static final List<CraftRecipe> RECIPES =
      List.of(
          new CraftRecipe(
              "bread",
              false,
              true,
              Map.of(Items.WHEAT, 3),
              Items.BREAD,
              1),
          new CraftRecipe(
              "cookie",
              false,
              true,
              Map.of(Items.WHEAT, 2, Items.COCOA_BEANS, 1),
              Items.COOKIE,
              8),
          new CraftRecipe(
              "pumpkin_pie",
              false,
              true,
              Map.of(Items.PUMPKIN, 1, Items.SUGAR, 1, Items.EGG, 1),
              Items.PUMPKIN_PIE,
              1),
          new CraftRecipe(
              "mushroom_stew",
              false,
              true,
              Map.of(Items.BOWL, 1, Items.BROWN_MUSHROOM, 1, Items.RED_MUSHROOM, 1),
              Items.MUSHROOM_STEW,
              1),
          new CraftRecipe(
              "torch_coal",
              false,
              false,
              Map.of(Items.COAL, 1, Items.STICK, 1),
              Items.TORCH,
              4),
          new CraftRecipe(
              "torch_charcoal",
              false,
              false,
              Map.of(Items.CHARCOAL, 1, Items.STICK, 1),
              Items.TORCH,
              4),
          // Cooking for Blockheads / Farmer's Delight cooking station recipes
          new CraftRecipe("cooked_beef", true, true, Map.of(Items.BEEF, 1), Items.COOKED_BEEF, 1),
          new CraftRecipe(
              "cooked_pork", true, true, Map.of(Items.PORKCHOP, 1), Items.COOKED_PORKCHOP, 1),
          new CraftRecipe(
              "cooked_chicken", true, true, Map.of(Items.CHICKEN, 1), Items.COOKED_CHICKEN, 1),
          new CraftRecipe(
              "cooked_mutton", true, true, Map.of(Items.MUTTON, 1), Items.COOKED_MUTTON, 1),
          new CraftRecipe(
              "baked_potato", true, true, Map.of(Items.POTATO, 1), Items.BAKED_POTATO, 1),
          new CraftRecipe("cooked_cod", true, true, Map.of(Items.COD, 1), Items.COOKED_COD, 1),
          new CraftRecipe(
              "cooked_salmon", true, true, Map.of(Items.SALMON, 1), Items.COOKED_SALMON, 1));

  private static final class ActiveCraft {
    final BlockPos stationPos;
    final CraftRecipe recipe;
    int remainingTicks;

    ActiveCraft(BlockPos stationPos, CraftRecipe recipe, int remainingTicks) {
      this.stationPos = stationPos;
      this.recipe = recipe;
      this.remainingTicks = remainingTicks;
    }
  }

  private static final class PlayerRuntime {
    ActiveCraft activeCraft;
    long nextHarvestTick;
    long nextHarvestMoodRollTick;
    int harvestMoodRemaining;
    long nextCraftScanTick;
    long nextTorchTick;
    long nextCombatTick;
    long nextShieldTick;
    long nextHealTick;
    int markedTargetId;
    long markedTargetUntilTick;
    long nextThrowBonkTick;
  }

  private static final Map<UUID, PlayerRuntime> RUNTIME = new HashMap<>();
  private static boolean registered;

  private CubeyServerHelper() {}

  public static void register() {
    if (registered) return;
    registered = true;
    CubeyNetwork.serverPackActionHandler = CubeyServerHelper::handlePackAction;
    CubeyNetwork.serverPackSlotClickHandler = CubeyServerHelper::handlePackSlotClick;
    CubeyNetwork.serverThrowHitMobHandler = CubeyServerHelper::handleThrowHitMob;
    NeoForge.EVENT_BUS.addListener(CubeyServerHelper::onServerTick);
    NeoForge.EVENT_BUS.addListener(CubeyServerHelper::onIncomingDamage);
    NeoForge.EVENT_BUS.addListener(CubeyServerHelper::onPlayerLogin);
    NeoForge.EVENT_BUS.addListener(CubeyServerHelper::onPlayerChangedDimension);
    NeoForge.EVENT_BUS.addListener(CubeyServerHelper::onPlayerRespawn);
    NeoForge.EVENT_BUS.addListener(CubeyServerHelper::onPlayerLogout);
  }

  private static void handlePackAction(
      ServerPlayer player, CubeyNetwork.C2SPackActionPayload payload) {
    if (!player.isAlive() || player.isSpectator()) return;
    CubeyPackStorage storage = CubeyPackStorage.get(player.server);
    CubeyPackStorage.PlayerPack pack = storage.getOrCreate(player.getUUID());
    switch (payload.action()) {
      case "open" -> CubeyPackMenu.openFor(player);
      case "toggle_flags" -> {
        pack.flags = payload.flags() & 0b1111;
        storage.setDirty();
        CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
      }
      case "request_sync" -> CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
      default -> {}
    }
  }

  /**
   * Direct server-authoritative slot click on Cubey's inline Inventory bar (slot 0 = Equipment,
   * slots 1..6 = 6 Backpack Slots). Supports LMB swap/merge, RMB split/place-one, and Shift-Click
   * quick-move into the player's inventory without opening any separate menu!
   */
  private static void handlePackSlotClick(
      ServerPlayer player, CubeyNetwork.C2SPackSlotClickPayload payload) {
    if (!player.isAlive() || player.isSpectator()) return;
    // Strict guard: inline inventory clicks are only valid while the player's own InventoryMenu is active
    if (player.containerMenu != player.inventoryMenu) return;
    int slot = payload.slot();
    if (slot < 0 || slot >= CubeyPackStorage.TOTAL_SLOTS) return;
    int button = payload.button();
    if (button != 0 && button != 1) return;

    CubeyPackStorage storage = CubeyPackStorage.get(player.server);
    CubeyPackStorage.PlayerPack pack = storage.getOrCreate(player.getUUID());
    ItemStack slotStack = pack.items.get(slot);
    ItemStack carried = player.containerMenu.getCarried();

    if (payload.shift()) {
      if (slotStack.isEmpty()) return;
      ItemStack moving = slotStack.copy();
      if (player.getInventory().add(moving)) {
        pack.items.set(slot, ItemStack.EMPTY);
      } else {
        pack.items.set(slot, moving);
      }
      player.containerMenu.broadcastFullState();
      storage.setDirty();
      CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
      return;
    }

    if (button == 0) {
      // LMB: place, merge, or swap
      if (!carried.isEmpty()
          && slot == CubeyPackStorage.EQUIP_SLOT
          && !CubeyPackStorage.isValidEquipment(carried)) {
        return;
      }
      if (slotStack.isEmpty()) {
        if (!carried.isEmpty()) {
          pack.items.set(slot, carried.copy());
          player.containerMenu.setCarried(ItemStack.EMPTY);
        }
      } else if (carried.isEmpty()) {
        player.containerMenu.setCarried(slotStack.copy());
        pack.items.set(slot, ItemStack.EMPTY);
      } else if (ItemStack.isSameItemSameComponents(slotStack, carried)
          && slotStack.getCount() < slotStack.getMaxStackSize()) {
        int move = Math.min(carried.getCount(), slotStack.getMaxStackSize() - slotStack.getCount());
        slotStack.grow(move);
        carried.shrink(move);
        player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
      } else {
        pack.items.set(slot, carried.copy());
        player.containerMenu.setCarried(slotStack.copy());
      }
    } else {
      // RMB: split half onto empty cursor, or place 1 from cursor into slot
      if (carried.isEmpty()) {
        if (!slotStack.isEmpty()) {
          int take = (slotStack.getCount() + 1) / 2;
          ItemStack picked = slotStack.split(take);
          if (slotStack.isEmpty()) pack.items.set(slot, ItemStack.EMPTY);
          player.containerMenu.setCarried(picked);
        }
      } else {
        if (slot == CubeyPackStorage.EQUIP_SLOT && !CubeyPackStorage.isValidEquipment(carried)) {
          return;
        }
        if (slotStack.isEmpty()) {
          pack.items.set(slot, carried.split(1));
          player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        } else if (ItemStack.isSameItemSameComponents(slotStack, carried)
            && slotStack.getCount() < slotStack.getMaxStackSize()) {
          slotStack.grow(1);
          carried.shrink(1);
          player.containerMenu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
      }
    }

    player.containerMenu.broadcastFullState();
    storage.setDirty();
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
  }

  /**
   * Throwing Cubey directly at a hostile or neutral/passive-aggressive mob bonks it gently and
   * marks it as Cubey's priority combat target for 15 seconds!
   */
  private static void handleThrowHitMob(
      ServerPlayer player, CubeyNetwork.C2SThrowHitMobPayload payload) {
    if (!player.isAlive() || player.isSpectator()) return;
    if (!(player.level() instanceof ServerLevel level)) return;
    long tick = player.server.getTickCount();
    PlayerRuntime rt = RUNTIME.computeIfAbsent(player.getUUID(), id -> new PlayerRuntime());
    if (tick < rt.nextThrowBonkTick) return;

    var entity = level.getEntity(payload.entityId());
    if (!(entity instanceof net.minecraft.world.entity.Mob mob) || !mob.isAlive()) return;
    if (mob instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame()) return;
    if (mob instanceof net.minecraft.world.entity.npc.AbstractVillager) return;
    if (mob.distanceToSqr(player) > 28.0 * 28.0) return;

    rt.nextThrowBonkTick = tick + 18;
    rt.markedTargetId = mob.getId();
    rt.markedTargetUntilTick = tick + 300; // 15s focus mark!

    mob.hurt(level.damageSources().playerAttack(player), 1.5f);
    Vec3 push = mob.position().subtract(player.position());
    if (push.lengthSqr() > 0.001) {
      push = push.normalize();
      mob.push(push.x * 0.45, 0.22, push.z * 0.45);
      mob.hurtMarked = true;
    }
    level.sendParticles(
        ParticleTypes.CRIT,
        mob.getX(),
        mob.getY() + mob.getBbHeight() * 0.6,
        mob.getZ(),
        6,
        0.25,
        0.2,
        0.25,
        0.04);
    level.playSound(
        null,
        mob.blockPosition(),
        SoundEvents.PLAYER_ATTACK_KNOCKBACK,
        SoundSource.PLAYERS,
        0.65f,
        1.35f);
  }

  private static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    syncPack(player);
  }

  private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    syncPack(player);
  }

  private static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    syncPack(player);
  }

  private static void syncPack(ServerPlayer player) {
    CubeyPackStorage storage = CubeyPackStorage.get(player.server);
    CubeyPackStorage.PlayerPack pack = storage.getOrCreate(player.getUUID());
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
  }

  private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
    RUNTIME.remove(event.getEntity().getUUID());
  }

  private static void onServerTick(ServerTickEvent.Post event) {
    MinecraftServer server = event.getServer();
    long tick = server.getTickCount();
    CubeyPackStorage storage = CubeyPackStorage.get(server);
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      if (!player.isAlive() || player.isSpectator()) continue;
      if (!(player.level() instanceof ServerLevel level)) continue;
      // Only run helper automation for players who actually have QB2 channel active
      if (!player.connection.hasChannel(CubeyNetwork.S2CPackSyncPayload.TYPE)) continue;

      CubeyPackStorage.PlayerPack pack = storage.getOrCreate(player.getUUID());
      PlayerRuntime rt = RUNTIME.computeIfAbsent(player.getUUID(), id -> new PlayerRuntime());

      tickActiveCraft(level, player, storage, pack, rt, tick);
      if (tick % 10 == 0 && pack.flagEnabled(CubeyPackStorage.FLAG_COMBAT)) {
        tickCombatSupport(level, player, storage, pack, rt, tick);
      }
      if (tick >= rt.nextCraftScanTick
          && rt.activeCraft == null
          && pack.flagEnabled(CubeyPackStorage.FLAG_CRAFT)) {
        rt.nextCraftScanTick = tick + 40;
        tryStartVisualCraft(level, player, pack, rt);
      }
      if (tick >= rt.nextHarvestTick
          && rt.activeCraft == null
          && pack.flagEnabled(CubeyPackStorage.FLAG_HARVEST)) {
        tickMoodBasedHarvest(level, player, storage, pack, rt, tick);
      }
      if (tick >= rt.nextTorchTick && pack.flagEnabled(CubeyPackStorage.FLAG_TORCH)) {
        rt.nextTorchTick = tick + 30;
        tryPlaceTorch(level, player, storage, pack, rt, tick);
      }
    }
  }

  /**
   * 3-second visual crafting choreography: Cubey visibly works at the Crafting Table / Cooking
   * Station first; ingredients are consumed and Bread/Food/Torches appear only when finished!
   */
  private static void tickActiveCraft(
      ServerLevel level,
      ServerPlayer player,
      CubeyPackStorage storage,
      CubeyPackStorage.PlayerPack pack,
      PlayerRuntime rt,
      long tick) {
    ActiveCraft craft = rt.activeCraft;
    if (craft == null) return;
    // Cancel if player walked far away or station was removed
    if (player.blockPosition().distSqr(craft.stationPos) > 10 * 10
        || !isCraftOrCookStation(level.getBlockState(craft.stationPos))) {
      rt.activeCraft = null;
      return;
    }
    craft.remainingTicks--;
    if (craft.remainingTicks % 15 == 0 && craft.remainingTicks > 0) {
      level.sendParticles(
          craft.recipe.isFood ? ParticleTypes.CLOUD : ParticleTypes.CRIT,
          craft.stationPos.getX() + 0.5,
          craft.stationPos.getY() + 1.15,
          craft.stationPos.getZ() + 0.5,
          3,
          0.18,
          0.12,
          0.18,
          0.01);
      level.playSound(
          null,
          craft.stationPos,
          craft.recipe.isFood ? SoundEvents.CAMPFIRE_CRACKLE : SoundEvents.WOOD_HIT,
          SoundSource.PLAYERS,
          0.35f,
          1.35f);
    }
    if (craft.remainingTicks > 0) return;

    rt.activeCraft = null;
    // Re-verify real ingredients are still present (100% dupe-safe)
    if (!hasRecipeInputs(pack, craft.recipe)) return;
    ItemStack result = new ItemStack(craft.recipe.output, craft.recipe.outputCount);
    if (!pack.hasBackpackSpaceFor(result)) return;

    for (var entry : craft.recipe.inputs.entrySet()) {
      Item target = entry.getKey();
      pack.consumeFromBackpack(st -> st.is(target), entry.getValue());
    }
    ItemStack leftover = pack.insertIntoBackpack(result);
    if (!leftover.isEmpty()) {
      Block.popResource(level, craft.stationPos.above(), leftover);
    }
    storage.setDirty();
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
    String outId = BuiltInRegistries.ITEM.getKey(craft.recipe.output).toString();
    CubeyNetwork.sendTaskVisual(
        player,
        new CubeyNetwork.S2CTaskVisualPayload(
            "craft_done",
            craft.stationPos.getX() + 0.5,
            craft.stationPos.getY() + 1.15,
            craft.stationPos.getZ() + 0.5,
            outId,
            35));
    level.sendParticles(
        ParticleTypes.HAPPY_VILLAGER,
        craft.stationPos.getX() + 0.5,
        craft.stationPos.getY() + 1.2,
        craft.stationPos.getZ() + 0.5,
        6,
        0.25,
        0.2,
        0.25,
        0.02);
    level.playSound(
        null,
        craft.stationPos,
        SoundEvents.EXPERIENCE_ORB_PICKUP,
        SoundSource.PLAYERS,
        0.4f,
        1.45f);
  }

  private static void tryStartVisualCraft(
      ServerLevel level,
      ServerPlayer player,
      CubeyPackStorage.PlayerPack pack,
      PlayerRuntime rt) {
    StationMatch station = findNearbyStation(level, player.blockPosition(), 6);
    if (station == null) return;
    for (CraftRecipe recipe : RECIPES) {
      if (recipe.cookingStationOnly && !station.isCooking) continue;
      // Don't burn all coal into torches once Cubey already carries >= 16 torches
      if (recipe.output == Items.TORCH && pack.countInBackpack(st -> st.is(Items.TORCH)) >= 16) {
        continue;
      }
      if (!hasRecipeInputs(pack, recipe)) continue;
      if (!pack.hasBackpackSpaceFor(new ItemStack(recipe.output, recipe.outputCount))) continue;

      rt.activeCraft = new ActiveCraft(station.pos, recipe, CRAFT_DURATION_TICKS);
      String outId = BuiltInRegistries.ITEM.getKey(recipe.output).toString();
      CubeyNetwork.sendTaskVisual(
          player,
          new CubeyNetwork.S2CTaskVisualPayload(
              recipe.isFood ? "cook_start" : "craft_start",
              station.pos.getX() + 0.5,
              station.pos.getY() + 1.2,
              station.pos.getZ() + 0.5,
              outId,
              CRAFT_DURATION_TICKS));
      return;
    }
  }

  private static boolean hasRecipeInputs(CubeyPackStorage.PlayerPack pack, CraftRecipe recipe) {
    for (var entry : recipe.inputs.entrySet()) {
      Item item = entry.getKey();
      if (pack.countInBackpack(st -> st.is(item)) < entry.getValue()) return false;
    }
    return true;
  }

  private record StationMatch(BlockPos pos, boolean isCooking) {}

  private static StationMatch findNearbyStation(ServerLevel level, BlockPos origin, int radius) {
    StationMatch best = null;
    double bestDistSq = Double.MAX_VALUE;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
          BlockState state = level.getBlockState(cursor);
          if (!isCraftOrCookStation(state)) continue;
          double d2 = origin.distSqr(cursor);
          if (d2 < bestDistSq) {
            bestDistSq = d2;
            best = new StationMatch(cursor.immutable(), isCookingStation(state));
          }
        }
      }
    }
    return best;
  }

  private static boolean isCraftOrCookStation(BlockState state) {
    if (state.is(Blocks.CRAFTING_TABLE)) return true;
    return isCookingStation(state);
  }

  private static boolean isCookingStation(BlockState state) {
    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    return id.contains("cooking_table")
        || id.contains("cooking_pot")
        || id.contains("skillet")
        || id.contains("cutting_board");
  }

  /**
   * Mood-driven gentle gardening: Cubey does NOT farm nonstop. He occasionally gets into a cozy
   * gardening mood (3–5 crops), only when the player is relaxed (not sprinting/fighting), and NEVER
   * harvests a crop unless 100% of its drops fit cleanly inside his 6-slot backpack (zero ground
   * item spam!).
   */
  private static void tickMoodBasedHarvest(
      ServerLevel level,
      ServerPlayer player,
      CubeyPackStorage storage,
      CubeyPackStorage.PlayerPack pack,
      PlayerRuntime rt,
      long tick) {
    // Pause gardening while sprinting or recently in combat
    if (player.isSprinting() || (player.tickCount - player.getLastHurtByMobTimestamp()) < 140) {
      rt.nextHarvestTick = tick + 60;
      return;
    }
    if (rt.harvestMoodRemaining <= 0) {
      if (tick < rt.nextHarvestMoodRollTick) {
        rt.nextHarvestTick = tick + 60;
        return;
      }
      // Roll every 20s (~22% chance) to enter a cozy gardening mood of 3..5 crops
      rt.nextHarvestMoodRollTick = tick + 400;
      rt.nextHarvestTick = tick + 60;
      if (level.random.nextFloat() >= 0.22f) return;
      rt.harvestMoodRemaining = 3 + level.random.nextInt(3);
    }

    rt.nextHarvestTick = tick + 55; // Cozy ~2.75s cadence per crop
    boolean harvested = tryGentleHarvest(level, player, storage, pack);
    if (harvested) {
      rt.harvestMoodRemaining--;
      if (rt.harvestMoodRemaining <= 0) {
        // Rest 2.0–3.5 minutes after a gardening wave so Cubey feels playful, not industrial
        rt.nextHarvestMoodRollTick = tick + 2400 + level.random.nextInt(1800);
      }
    } else {
      // No mature crop nearby or backpack full -> end gardening mood cleanly & wait a minute
      rt.harvestMoodRemaining = 0;
      rt.nextHarvestMoodRollTick = tick + 1200;
    }
  }

  private static boolean tryGentleHarvest(
      ServerLevel level,
      ServerPlayer player,
      CubeyPackStorage storage,
      CubeyPackStorage.PlayerPack pack) {
    BlockPos origin = player.blockPosition();
    BlockPos bestCrop = null;
    double bestDistSq = Double.MAX_VALUE;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -6; dx <= 6; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        for (int dz = -6; dz <= 6; dz++) {
          cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
          BlockState st = level.getBlockState(cursor);
          if (st.getBlock() instanceof CropBlock crop && crop.isMaxAge(st)) {
            double d2 = origin.distSqr(cursor);
            if (d2 < bestDistSq) {
              bestDistSq = d2;
              bestCrop = cursor.immutable();
            }
          }
        }
      }
    }
    if (bestCrop == null) return false;
    BlockState state = level.getBlockState(bestCrop);
    if (!(state.getBlock() instanceof CropBlock crop)) return false;

    List<ItemStack> rawDrops =
        Block.getDrops(state, level, bestCrop, null, player, ItemStack.EMPTY);
    if (rawDrops.isEmpty()) return false;

    // Deduct 1 seed up-front to pay for replanting and verify 100% of remaining drops fit in pack!
    List<ItemStack> netDrops = new ArrayList<>(rawDrops.size());
    boolean seedPaid = false;
    for (ItemStack drop : rawDrops) {
      if (drop.isEmpty()) continue;
      ItemStack copy = drop.copy();
      if (!seedPaid && copy.getCount() > 0 && isSeedLike(copy)) {
        copy.shrink(1);
        seedPaid = true;
      }
      if (!copy.isEmpty()) netDrops.add(copy);
    }
    if (netDrops.isEmpty()) return false;
    // STRICT CHECK: Never harvest if Cubey's 6-slot backpack cannot hold all drops!
    if (!pack.canFitAllInBackpack(netDrops)) return false;

    // Replant in-place without breaking the block!
    level.setBlock(bestCrop, crop.getStateForAge(0), Block.UPDATE_ALL);

    String harvestedItemId = BuiltInRegistries.ITEM.getKey(netDrops.get(0).getItem()).toString();
    for (ItemStack stack : netDrops) {
      pack.insertIntoBackpack(stack);
    }

    storage.setDirty();
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
    CubeyNetwork.sendTaskVisual(
        player,
        new CubeyNetwork.S2CTaskVisualPayload(
            "harvest",
            bestCrop.getX() + 0.5,
            bestCrop.getY() + 0.6,
            bestCrop.getZ() + 0.5,
            harvestedItemId,
            26));
    level.sendParticles(
        ParticleTypes.HAPPY_VILLAGER,
        bestCrop.getX() + 0.5,
        bestCrop.getY() + 0.45,
        bestCrop.getZ() + 0.5,
        4,
        0.2,
        0.15,
        0.2,
        0.01);
    level.playSound(
        null, bestCrop, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.45f, 1.25f);
    return true;
  }

  private static boolean isSeedLike(ItemStack stack) {
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    return id.contains("seed")
        || stack.is(Items.CARROT)
        || stack.is(Items.POTATO)
        || stack.is(Items.BEETROOT_SEEDS);
  }

  private static void tryPlaceTorch(
      ServerLevel level,
      ServerPlayer player,
      CubeyPackStorage storage,
      CubeyPackStorage.PlayerPack pack,
      PlayerRuntime rt,
      long tick) {
    if (!player.onGround() || player.isInWater() || player.isInLava()) return;
    if (pack.countInBackpack(st -> st.is(Items.TORCH)) <= 0) return;

    BlockPos feet = player.blockPosition();
    if (level.getBrightness(LightLayer.BLOCK, feet) > 0) return;
    if (level.getBrightness(LightLayer.SKY, feet) > 4 && level.isDay()) return;
    if (!level.getBlockState(feet).isAir()) return;
    if (!Blocks.TORCH.defaultBlockState().canSurvive(level, feet)) return;

    if (!pack.consumeFromBackpack(st -> st.is(Items.TORCH), 1)) return;
    level.setBlock(feet, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
    rt.nextTorchTick = tick + 80; // 4s cooldown
    storage.setDirty();
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
    CubeyNetwork.sendTaskVisual(
        player,
        new CubeyNetwork.S2CTaskVisualPayload(
            "torch",
            feet.getX() + 0.5,
            feet.getY() + 0.5,
            feet.getZ() + 0.5,
            "minecraft:torch",
            20));
    level.playSound(null, feet, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.6f, 1.15f);
  }

  private static void tickCombatSupport(
      ServerLevel level,
      ServerPlayer player,
      CubeyPackStorage storage,
      CubeyPackStorage.PlayerPack pack,
      PlayerRuntime rt,
      long tick) {
    ItemStack eq = pack.equipment();
    if (eq.isEmpty()) return;

    // 1. Emergency Heal (Healing/Regen Potion or Golden Apple) when player drops below 30% HP
    if ((isHealingPotion(eq)
            || eq.is(Items.GOLDEN_APPLE)
            || eq.is(Items.ENCHANTED_GOLDEN_APPLE))
        && player.getHealth() <= Math.max(6.0f, player.getMaxHealth() * 0.30f)
        && tick >= rt.nextHealTick) {
      rt.nextHealTick = tick + 400; // 20s cooldown
      boolean gapple = eq.is(Items.GOLDEN_APPLE) || eq.is(Items.ENCHANTED_GOLDEN_APPLE);
      eq.shrink(1);
      if (eq.isEmpty()) pack.setEquipment(ItemStack.EMPTY);
      player.heal(gapple ? 6.0f : 8.0f);
      player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, gapple ? 120 : 80, 1));
      if (gapple) {
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0));
      }
      storage.setDirty();
      CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
      CubeyNetwork.sendTaskVisual(
          player,
          new CubeyNetwork.S2CTaskVisualPayload(
              "combat_heal",
              player.getX(),
              player.getY() + 1.5,
              player.getZ(),
              "minecraft:golden_apple",
              30));
      level.sendParticles(
          ParticleTypes.HEART,
          player.getX(),
          player.getY() + 1.4,
          player.getZ(),
          8,
          0.4,
          0.3,
          0.4,
          0.02);
      level.playSound(
          null, player.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.7f, 1.1f);
      return;
    }

    if (tick < rt.nextCombatTick) return;
    net.minecraft.world.entity.Mob target = findThreateningMonster(level, player, rt, tick, 10.0);
    if (target == null) return;

    Vec3 shoulder = player.getEyePosition().add(0, 0.55, 0);

    // 2. Bow / Crossbow + real Arrow from backpack
    if ((eq.getItem() instanceof BowItem || eq.getItem() instanceof CrossbowItem)
        && pack.countInBackpack(st -> st.getItem() instanceof ArrowItem) > 0) {
      if (pack.consumeFromBackpack(st -> st.getItem() instanceof ArrowItem, 1)) {
        Arrow arrow = new Arrow(level, shoulder.x, shoulder.y, shoulder.z, new ItemStack(Items.ARROW), eq);
        arrow.setOwner(player);
        arrow.setBaseDamage(1.3); // Support damage (~45% of full bow)
        arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        Vec3 dir = target.getEyePosition().subtract(shoulder);
        arrow.shoot(dir.x, dir.y + dir.horizontalDistance() * 0.14, dir.z, 1.9f, 2.5f);
        level.addFreshEntity(arrow);
        damageEquip(pack, eq, 1);
        rt.nextCombatTick = tick + 70; // 3.5s
        storage.setDirty();
        CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
        CubeyNetwork.sendTaskVisual(
            player,
            new CubeyNetwork.S2CTaskVisualPayload(
                "combat_shoot",
                target.getX(),
                target.getY() + target.getBbHeight() * 0.6,
                target.getZ(),
                BuiltInRegistries.ITEM.getKey(eq.getItem()).toString(),
                18));
        level.playSound(
            null, player.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 0.65f, 1.25f);
      }
      return;
    }

    // 3. Sword / Axe -> Close-range Knockback-Dash (great against Creepers!)
    if ((eq.getItem() instanceof SwordItem || eq.getItem() instanceof AxeItem)
        && target.distanceToSqr(player) <= 4.2 * 4.2) {
      target.hurt(level.damageSources().playerAttack(player), 3.0f);
      Vec3 push = target.position().subtract(player.position()).normalize();
      target.push(push.x * 0.65, 0.28, push.z * 0.65);
      target.hurtMarked = true;
      damageEquip(pack, eq, 1);
      rt.nextCombatTick = tick + 60; // 3.0s
      storage.setDirty();
      CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
      CubeyNetwork.sendTaskVisual(
          player,
          new CubeyNetwork.S2CTaskVisualPayload(
              "combat_dash",
              target.getX(),
              target.getY() + target.getBbHeight() * 0.6,
              target.getZ(),
              BuiltInRegistries.ITEM.getKey(eq.getItem()).toString(),
              16));
      level.sendParticles(
          ParticleTypes.SWEEP_ATTACK,
          target.getX(),
          target.getY() + 0.9,
          target.getZ(),
          1,
          0,
          0,
          0,
          0);
      level.playSound(
          null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.6f, 1.3f);
      return;
    }

    // 4. Amethyst Shard Module -> Mini Laser Zap (zero consumption!)
    if (eq.is(Items.AMETHYST_SHARD) && target.distanceToSqr(player) <= 7.5 * 7.5) {
      target.hurt(level.damageSources().magic(), 2.0f);
      Vec3 push = target.position().subtract(player.position()).normalize();
      target.push(push.x * 0.35, 0.15, push.z * 0.35);
      rt.nextCombatTick = tick + 80; // 4.0s
      CubeyNetwork.sendTaskVisual(
          player,
          new CubeyNetwork.S2CTaskVisualPayload(
              "combat_zap",
              target.getX(),
              target.getY() + target.getBbHeight() * 0.6,
              target.getZ(),
              "minecraft:amethyst_shard",
              16));
      DustParticleOptions purple = new DustParticleOptions(new Vector3f(0.75f, 0.35f, 1.0f), 0.9f);
      Vec3 end = target.getEyePosition();
      for (int i = 1; i <= 6; i++) {
        double t = i / 6.0;
        level.sendParticles(
            purple,
            shoulder.x + (end.x - shoulder.x) * t,
            shoulder.y + (end.y - shoulder.y) * t,
            shoulder.z + (end.z - shoulder.z) * t,
            1,
            0,
            0,
            0,
            0);
      }
      level.playSound(
          null,
          target.blockPosition(),
          SoundEvents.AMETHYST_BLOCK_CHIME,
          SoundSource.PLAYERS,
          0.7f,
          1.6f);
      return;
    }

    // 5. Glowstone Dust Module -> Flash-Stun (Glowing + brief Slowness)
    if (eq.is(Items.GLOWSTONE_DUST) && target.distanceToSqr(player) <= 8.5 * 8.5) {
      target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0));
      target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0));
      rt.nextCombatTick = tick + 120; // 6.0s
      CubeyNetwork.sendTaskVisual(
          player,
          new CubeyNetwork.S2CTaskVisualPayload(
              "combat_flash",
              target.getX(),
              target.getY() + target.getBbHeight() * 0.6,
              target.getZ(),
              "minecraft:glowstone_dust",
              16));
      level.sendParticles(
          ParticleTypes.FLASH, target.getX(), target.getY() + 1.0, target.getZ(), 1, 0, 0, 0, 0);
      level.playSound(
          null,
          target.blockPosition(),
          SoundEvents.AMETHYST_CLUSTER_BREAK,
          SoundSource.PLAYERS,
          0.5f,
          1.8f);
    }
  }

  private static void onIncomingDamage(LivingIncomingDamageEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) return;
    if (!(player.level() instanceof ServerLevel level)) return;
    if (!player.connection.hasChannel(CubeyNetwork.S2CPackSyncPayload.TYPE)) return;

    CubeyPackStorage storage = CubeyPackStorage.get(player.server);
    CubeyPackStorage.PlayerPack pack = storage.getOrCreate(player.getUUID());
    if (!pack.flagEnabled(CubeyPackStorage.FLAG_COMBAT)) return;
    ItemStack eq = pack.equipment();
    if (!(eq.getItem() instanceof ShieldItem)) return;

    PlayerRuntime rt = RUNTIME.computeIfAbsent(player.getUUID(), id -> new PlayerRuntime());
    long tick = player.server.getTickCount();
    if (tick < rt.nextShieldTick) return;
    if (event.getSource().getEntity() == null) return;

    rt.nextShieldTick = tick + 300; // 15s shield intercept cooldown
    damageEquip(pack, eq, 1);
    storage.setDirty();
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());

    if (event.getSource().isDirect()) {
      event.setAmount(event.getAmount() * 0.5f);
    } else {
      event.setCanceled(true); // Intercepts incoming arrow/projectile cleanly!
    }
    CubeyNetwork.sendTaskVisual(
        player,
        new CubeyNetwork.S2CTaskVisualPayload(
            "combat_shield",
            player.getX(),
            player.getY() + 1.2,
            player.getZ(),
            "minecraft:shield",
            22));
    level.playSound(
        null, player.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.85f, 1.2f);
  }

  private static boolean isHealingPotion(ItemStack stack) {
    if (!(stack.getItem() instanceof PotionItem)) return false;
    var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
    if (contents == null) return false;
    for (MobEffectInstance eff : contents.getAllEffects()) {
      if (eff.is(MobEffects.HEAL) || eff.is(MobEffects.REGENERATION)) return true;
    }
    return false;
  }

  private static void damageEquip(CubeyPackStorage.PlayerPack pack, ItemStack eq, int amount) {
    if (!eq.isDamageableItem()) return;
    eq.setDamageValue(eq.getDamageValue() + amount);
    if (eq.getDamageValue() >= eq.getMaxDamage()) {
      pack.setEquipment(ItemStack.EMPTY);
    }
  }

  private static boolean isNeutralOrPassiveAggressive(net.minecraft.world.entity.Mob m) {
    if (m instanceof net.minecraft.world.entity.NeutralMob) return true;
    String id = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
    return id.contains("zombified_piglin")
        || id.contains("piglin")
        || id.contains("enderman")
        || id.contains("spider");
  }

  private static net.minecraft.world.entity.Mob findThreateningMonster(
      ServerLevel level, ServerPlayer player, PlayerRuntime rt, long tick, double radius) {
    // 1. Highest priority: mob explicitly marked by throwing Cubey at it!
    if (rt.markedTargetId > 0 && tick <= rt.markedTargetUntilTick) {
      var ent = level.getEntity(rt.markedTargetId);
      if (ent instanceof net.minecraft.world.entity.Mob marked
          && marked.isAlive()
          && !marked.isInvisible()
          && marked.distanceToSqr(player) <= (radius + 4.0) * (radius + 4.0)
          && player.hasLineOfSight(marked)) {
        return marked;
      }
    }
    AABB box = player.getBoundingBox().inflate(radius, 4.0, radius);
    Monster best = null;
    double bestDistSq = radius * radius;
    LivingEntity lastHurt = player.getLastHurtMob();
    for (Monster m : level.getEntitiesOfClass(Monster.class, box)) {
      if (!m.isAlive() || m.isInvisible()) continue;
      boolean neutral = isNeutralOrPassiveAggressive(m);
      // NEVER auto-aggro neutral/passive-aggressive mobs (e.g. Zombified Piglins, Endermen) from
      // proximity alone — only engage them if they target the player, were hit, or Cubey was thrown!
      boolean relevant =
          neutral
              ? (m.getTarget() == player || m == lastHurt)
              : (m.getTarget() == player || m == lastHurt || m.distanceToSqr(player) <= 16.0);
      if (!relevant) continue;
      if (!player.hasLineOfSight(m)) continue;
      double d2 = m.distanceToSqr(player);
      if (d2 < bestDistSq) {
        bestDistSq = d2;
        best = m;
      }
    }
    return best;
  }
}
