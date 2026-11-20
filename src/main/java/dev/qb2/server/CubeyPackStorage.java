package dev.qb2.server;

import dev.qb2.network.CubeyNetwork;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.*;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Overworld-persisted server storage for every player's Cubey Equipment Slot (slot 0) and 6-slot
 * Mini-Backpack (slots 1..6) plus helper automation toggles. Zero custom items or entities are
 * registered, keeping non-modded clients 100% compatible.
 */
public final class CubeyPackStorage extends SavedData {
  public static final String DATA_NAME = "qb2_cubey_packs";
  public static final int EQUIP_SLOT = 0;
  public static final int BACKPACK_START = 1;
  public static final int BACKPACK_SIZE = 6;
  public static final int TOTAL_SLOTS = 1 + BACKPACK_SIZE; // 7 real slots

  public static final int FLAG_HARVEST = 1 << 0;
  public static final int FLAG_CRAFT = 1 << 1;
  public static final int FLAG_TORCH = 1 << 2;
  public static final int FLAG_COMBAT = 1 << 3;
  public static final int DEFAULT_FLAGS = FLAG_HARVEST | FLAG_CRAFT | FLAG_TORCH | FLAG_COMBAT;

  public static final class PlayerPack {
    public final NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
    public int flags = DEFAULT_FLAGS;

    public ItemStack equipment() {
      return items.get(EQUIP_SLOT);
    }

    public void setEquipment(ItemStack stack) {
      items.set(EQUIP_SLOT, stack == null ? ItemStack.EMPTY : stack);
    }

    public boolean flagEnabled(int bit) {
      return (flags & bit) != 0;
    }

    /** Inserts as much of {@code stack} as possible into backpack slots 1..6. Returns remainder. */
    public ItemStack insertIntoBackpack(ItemStack stack) {
      if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
      ItemStack rem = stack.copy();
      // Pass 1: stack onto existing matching stacks
      for (int i = BACKPACK_START; i < TOTAL_SLOTS && !rem.isEmpty(); i++) {
        ItemStack slot = items.get(i);
        if (!slot.isEmpty()
            && ItemStack.isSameItemSameComponents(slot, rem)
            && slot.getCount() < slot.getMaxStackSize()) {
          int move = Math.min(rem.getCount(), slot.getMaxStackSize() - slot.getCount());
          slot.grow(move);
          rem.shrink(move);
        }
      }
      // Pass 2: place into empty slots
      for (int i = BACKPACK_START; i < TOTAL_SLOTS && !rem.isEmpty(); i++) {
        if (items.get(i).isEmpty()) {
          int move = Math.min(rem.getCount(), rem.getMaxStackSize());
          items.set(i, rem.split(move));
        }
      }
      return rem;
    }

    public int countInBackpack(Predicate<ItemStack> matcher) {
      int total = 0;
      for (int i = BACKPACK_START; i < TOTAL_SLOTS; i++) {
        ItemStack st = items.get(i);
        if (!st.isEmpty() && matcher.test(st)) total += st.getCount();
      }
      return total;
    }

    public boolean hasBackpackSpaceFor(ItemStack sample) {
      if (sample == null || sample.isEmpty()) return true;
      int needed = sample.getCount();
      for (int i = BACKPACK_START; i < TOTAL_SLOTS; i++) {
        ItemStack slot = items.get(i);
        if (slot.isEmpty()) return true;
        if (ItemStack.isSameItemSameComponents(slot, sample)) {
          needed -= (slot.getMaxStackSize() - slot.getCount());
          if (needed <= 0) return true;
        }
      }
      return false;
    }

    /**
     * Simulates inserting every stack into slots 1..6 and returns {@code true} only if ALL items
     * fit completely without dropping anything on the ground.
     */
    public boolean canFitAllInBackpack(List<ItemStack> stacks) {
      if (stacks == null || stacks.isEmpty()) return true;
      ItemStack[] sim = new ItemStack[BACKPACK_SIZE];
      for (int i = 0; i < BACKPACK_SIZE; i++) {
        sim[i] = items.get(BACKPACK_START + i).copy();
      }
      for (ItemStack raw : stacks) {
        if (raw == null || raw.isEmpty()) continue;
        ItemStack rem = raw.copy();
        for (int i = 0; i < BACKPACK_SIZE && !rem.isEmpty(); i++) {
          if (!sim[i].isEmpty()
              && ItemStack.isSameItemSameComponents(sim[i], rem)
              && sim[i].getCount() < sim[i].getMaxStackSize()) {
            int move = Math.min(rem.getCount(), sim[i].getMaxStackSize() - sim[i].getCount());
            sim[i].grow(move);
            rem.shrink(move);
          }
        }
        for (int i = 0; i < BACKPACK_SIZE && !rem.isEmpty(); i++) {
          if (sim[i].isEmpty()) {
            int move = Math.min(rem.getCount(), rem.getMaxStackSize());
            sim[i] = rem.split(move);
          }
        }
        if (!rem.isEmpty()) return false;
      }
      return true;
    }

    /** Consumes exactly {@code amount} matching items from slots 1..6. Returns true if consumed. */
    public boolean consumeFromBackpack(Predicate<ItemStack> matcher, int amount) {
      if (amount <= 0) return true;
      if (countInBackpack(matcher) < amount) return false;
      int left = amount;
      for (int i = BACKPACK_START; i < TOTAL_SLOTS && left > 0; i++) {
        ItemStack st = items.get(i);
        if (!st.isEmpty() && matcher.test(st)) {
          int take = Math.min(left, st.getCount());
          st.shrink(take);
          if (st.isEmpty()) items.set(i, ItemStack.EMPTY);
          left -= take;
        }
      }
      return left == 0;
    }

    public CubeyNetwork.S2CPackSyncPayload toSyncPayload() {
      ItemStack eq = equipment();
      String eqId = eq.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(eq.getItem()).toString();
      List<String> ids = new ArrayList<>(BACKPACK_SIZE);
      List<Integer> counts = new ArrayList<>(BACKPACK_SIZE);
      for (int i = BACKPACK_START; i < TOTAL_SLOTS; i++) {
        ItemStack st = items.get(i);
        if (st.isEmpty()) {
          ids.add("");
          counts.add(0);
        } else {
          ids.add(BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
          counts.add(st.getCount());
        }
      }
      return new CubeyNetwork.S2CPackSyncPayload(eqId, eq.getCount(), ids, counts, flags);
    }
  }

  private final Map<UUID, PlayerPack> packs = new HashMap<>();

  public static boolean isValidEquipment(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return true;
    Item item = stack.getItem();
    if (item instanceof SwordItem
        || item instanceof AxeItem
        || item instanceof BowItem
        || item instanceof CrossbowItem
        || item instanceof ShieldItem
        || item instanceof PotionItem
        || item == Items.GOLDEN_APPLE
        || item == Items.ENCHANTED_GOLDEN_APPLE
        || item == Items.AMETHYST_SHARD
        || item == Items.GLOWSTONE_DUST
        || item == Items.LANTERN
        || item == Items.SOUL_LANTERN) {
      return true;
    }
    String id = BuiltInRegistries.ITEM.getKey(item).toString();
    return id.contains("sword")
        || id.contains("bow")
        || id.contains("shield")
        || id.contains("wand")
        || id.contains("staff")
        || id.contains("lantern");
  }

  public PlayerPack getOrCreate(UUID playerId) {
    return packs.computeIfAbsent(
        playerId,
        id -> {
          setDirty();
          return new PlayerPack();
        });
  }

  public static CubeyPackStorage get(MinecraftServer server) {
    ServerLevel overworld = server.overworld();
    return overworld
        .getDataStorage()
        .computeIfAbsent(
            new Factory<>(CubeyPackStorage::new, CubeyPackStorage::load), DATA_NAME);
  }

  private static CubeyPackStorage load(CompoundTag tag, HolderLookup.Provider registries) {
    CubeyPackStorage storage = new CubeyPackStorage();
    ListTag list = tag.getList("packs", Tag.TAG_COMPOUND);
    for (int i = 0; i < list.size(); i++) {
      CompoundTag entry = list.getCompound(i);
      if (!entry.hasUUID("uuid")) continue;
      UUID id = entry.getUUID("uuid");
      PlayerPack pack = new PlayerPack();
      pack.flags = entry.contains("flags") ? entry.getInt("flags") : DEFAULT_FLAGS;
      ContainerHelper.loadAllItems(entry, pack.items, registries);
      storage.packs.put(id, pack);
    }
    return storage;
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    ListTag list = new ListTag();
    for (var e : packs.entrySet()) {
      CompoundTag entry = new CompoundTag();
      entry.putUUID("uuid", e.getKey());
      entry.putInt("flags", e.getValue().flags);
      ContainerHelper.saveAllItems(entry, e.getValue().items, registries);
      list.add(entry);
    }
    tag.put("packs", list);
    return tag;
  }
}
