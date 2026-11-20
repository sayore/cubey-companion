package dev.qb2.server;

import dev.qb2.network.CubeyNetwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla {@link MenuType#GENERIC_9x1}-compatible menu exposing Cubey's dedicated Equipment Slot
 * (slot 0) and 6-slot Mini-Backpack (slots 1..6) while locking spacer slots 7..8. Because it uses a
 * standard vanilla menu type, zero custom menus or items are registered.
 */
public final class CubeyPackMenu extends ChestMenu {
  public static final String MENU_TITLE = "Cubey Rucksack";
  private final CubeyPackStorage storage;
  private final CubeyPackStorage.PlayerPack pack;
  private final ServerPlayer owner;

  private CubeyPackMenu(
      int containerId,
      Inventory playerInv,
      Container backing,
      CubeyPackStorage storage,
      CubeyPackStorage.PlayerPack pack,
      ServerPlayer owner) {
    super(MenuType.GENERIC_9x1, containerId, playerInv, backing, 1);
    this.storage = storage;
    this.pack = pack;
    this.owner = owner;
  }

  public static void openFor(ServerPlayer player) {
    CubeyPackStorage storage = CubeyPackStorage.get(player.server);
    CubeyPackStorage.PlayerPack pack = storage.getOrCreate(player.getUUID());
    Container backing = new BackingContainer(storage, pack, player);
    player.openMenu(
        new SimpleMenuProvider(
            (id, inv, p) -> new CubeyPackMenu(id, inv, backing, storage, pack, player),
            Component.literal(MENU_TITLE)));
    CubeyNetwork.sendPackSync(player, pack.toSyncPayload());
  }

  @Override
  public void clicked(int slotId, int button, ClickType clickType, Player player) {
    // Block locked spacer slots 7 & 8 completely
    if (slotId == 7 || slotId == 8) return;
    // Validate Equipment Slot (slot 0) on direct placement / swap
    if (slotId == CubeyPackStorage.EQUIP_SLOT) {
      ItemStack carried = getCarried();
      if (clickType == ClickType.PICKUP && !carried.isEmpty() && !CubeyPackStorage.isValidEquipment(carried)) {
        return;
      }
      if (clickType == ClickType.SWAP && button >= 0 && button < 9) {
        ItemStack hotbar = player.getInventory().getItem(button);
        if (!hotbar.isEmpty() && !CubeyPackStorage.isValidEquipment(hotbar)) {
          return;
        }
      }
    }
    super.clicked(slotId, button, clickType, player);
    storage.setDirty();
    CubeyNetwork.sendPackSync(owner, pack.toSyncPayload());
  }

  @Override
  public ItemStack quickMoveStack(Player player, int index) {
    if (index == 7 || index == 8) return ItemStack.EMPTY;
    Slot slot = this.slots.get(index);
    if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
    ItemStack stack = slot.getItem();
    ItemStack copy = stack.copy();

    if (index < 9) {
      // Move from Cubey Pack -> Player Inventory
      if (!this.moveItemStackTo(stack, 9, this.slots.size(), true)) {
        return ItemStack.EMPTY;
      }
    } else {
      // Move from Player Inventory -> Equipment Slot (if valid & empty) else Backpack Slots 1..6
      boolean moved = false;
      if (CubeyPackStorage.isValidEquipment(stack)
          && this.slots.get(CubeyPackStorage.EQUIP_SLOT).getItem().isEmpty()) {
        // Place 1 item into equipment slot if stackable, or whole stack if maxStackSize==1
        ItemStack single = stack.split(1);
        this.slots.get(CubeyPackStorage.EQUIP_SLOT).setByPlayer(single);
        moved = true;
      }
      if (!moved) {
        if (!this.moveItemStackTo(
            stack,
            CubeyPackStorage.BACKPACK_START,
            CubeyPackStorage.BACKPACK_START + CubeyPackStorage.BACKPACK_SIZE,
            false)) {
          return ItemStack.EMPTY;
        }
      }
    }

    if (stack.isEmpty()) {
      slot.setByPlayer(ItemStack.EMPTY);
    } else {
      slot.setChanged();
    }
    storage.setDirty();
    CubeyNetwork.sendPackSync(owner, pack.toSyncPayload());
    return copy;
  }

  @Override
  public void removed(Player player) {
    super.removed(player);
    storage.setDirty();
    CubeyNetwork.sendPackSync(owner, pack.toSyncPayload());
  }

  private record BackingContainer(
      CubeyPackStorage storage, CubeyPackStorage.PlayerPack pack, ServerPlayer owner)
      implements Container {
    @Override
    public int getContainerSize() {
      return 9;
    }

    @Override
    public boolean isEmpty() {
      for (ItemStack st : pack.items) if (!st.isEmpty()) return false;
      return true;
    }

    @Override
    public ItemStack getItem(int slot) {
      if (slot < 0 || slot >= CubeyPackStorage.TOTAL_SLOTS) return ItemStack.EMPTY;
      return pack.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
      if (slot < 0 || slot >= CubeyPackStorage.TOTAL_SLOTS) return ItemStack.EMPTY;
      ItemStack st = pack.items.get(slot);
      if (st.isEmpty()) return ItemStack.EMPTY;
      ItemStack split = st.split(amount);
      if (st.isEmpty()) pack.items.set(slot, ItemStack.EMPTY);
      setChanged();
      return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
      if (slot < 0 || slot >= CubeyPackStorage.TOTAL_SLOTS) return ItemStack.EMPTY;
      ItemStack st = pack.items.get(slot);
      pack.items.set(slot, ItemStack.EMPTY);
      return st;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
      if (slot < 0 || slot >= CubeyPackStorage.TOTAL_SLOTS) return;
      if (slot == CubeyPackStorage.EQUIP_SLOT && !CubeyPackStorage.isValidEquipment(stack)) return;
      pack.items.set(slot, stack == null ? ItemStack.EMPTY : stack);
      setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
      if (slot == CubeyPackStorage.EQUIP_SLOT) return CubeyPackStorage.isValidEquipment(stack);
      return slot >= CubeyPackStorage.BACKPACK_START && slot < CubeyPackStorage.TOTAL_SLOTS;
    }

    @Override
    public void setChanged() {
      storage.setDirty();
      CubeyNetwork.sendPackSync(owner, pack.toSyncPayload());
    }

    @Override
    public boolean stillValid(Player player) {
      return player.isAlive();
    }

    @Override
    public void clearContent() {
      for (int i = 0; i < CubeyPackStorage.TOTAL_SLOTS; i++) {
        pack.items.set(i, ItemStack.EMPTY);
      }
      setChanged();
    }
  }
}
