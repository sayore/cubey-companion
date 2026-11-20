package dev.qb2.perception.inventory;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Only locally owned inventory and slots of the currently visible, server-synced menu. */
public final class InventorySensor {
  public record Item(int slot, String area, String registryId, String name, int count) {}

  public record View(List<Item> player, List<Item> container, String title, int omittedSlots) {
    public List<String> keywords() {
      var words = new LinkedHashSet<String>();
      player.forEach(i -> words.add(i.registryId()));
      container.forEach(i -> words.add(i.registryId()));
      return List.copyOf(words);
    }
  }

  public static View capture(Minecraft mc) {
    if (mc.player == null) return new View(List.of(), List.of(), "", 0);
    var player = new ArrayList<Item>();
    var inventory = mc.player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) {
      var stack = inventory.getItem(i);
      if (!stack.isEmpty())
        player.add(
            item(i, i < 9 ? "hotbar" : i < 36 ? "backpack" : i < 40 ? "armor" : "offhand", stack));
    }
    var container = new ArrayList<Item>();
    String title = "";
    int omitted = 0;
    if (mc.screen instanceof AbstractContainerScreen<?> screen
        && screen.getMenu() == mc.player.containerMenu
        && mc.player.containerMenu != mc.player.inventoryMenu) {
      title = clip(screen.getTitle().getString());
      int visited = 0;
      for (var slot : mc.player.containerMenu.slots) {
        if (slot.container == inventory) continue;
        if (++visited > 256) {
          omitted++;
          continue;
        }
        if (slot.hasItem()) container.add(item(slot.index, "open_menu", slot.getItem()));
      }
    }
    return new View(List.copyOf(player), List.copyOf(container), title, omitted);
  }

  private static Item item(int slot, String area, ItemStack stack) {
    return new Item(
        slot,
        area,
        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
        clip(stack.getHoverName().getString()),
        stack.getCount());
  }

  private static String clip(String text) {
    return text.substring(0, Math.min(160, text.length()));
  }
}
