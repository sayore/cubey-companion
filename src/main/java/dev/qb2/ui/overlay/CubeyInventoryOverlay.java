package dev.qb2.ui.overlay;

import dev.qb2.network.CubeyNetwork;
import dev.qb2.server.CubeyPackMenu;
import dev.qb2.server.CubeyPackStorage;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Curios-aware Inventory Sidebar for Cubey's separate Equipment Slot + 6-Slot Backpack, plus visual
 * slot badges and clickable Helper Mode toggles inside the real {@link CubeyPackMenu} screen.
 */
public final class CubeyInventoryOverlay {
  private static volatile CubeyNetwork.S2CPackSyncPayload syncedPack =
      new CubeyNetwork.S2CPackSyncPayload(
          "",
          0,
          List.of("", "", "", "", "", ""),
          List.of(0, 0, 0, 0, 0, 0),
          CubeyPackStorage.DEFAULT_FLAGS);

  private static final int SIDEBAR_W = 58;
  private static final int SIDEBAR_H = 114;

  private CubeyInventoryOverlay() {}

  public static void updateSync(CubeyNetwork.S2CPackSyncPayload payload) {
    if (payload != null) syncedPack = payload;
  }

  public static void reset() {
    syncedPack =
        new CubeyNetwork.S2CPackSyncPayload(
            "",
            0,
            List.of("", "", "", "", "", ""),
            List.of(0, 0, 0, 0, 0, 0),
            CubeyPackStorage.DEFAULT_FLAGS);
  }

  public static String equippedItemId() {
    return syncedPack.equipItemId();
  }

  public static ItemStack resolveItem(String itemId, int count) {
    if (itemId == null || itemId.isBlank() || count <= 0) return ItemStack.EMPTY;
    ResourceLocation loc = ResourceLocation.tryParse(itemId);
    if (loc == null) return ItemStack.EMPTY;
    Item item = BuiltInRegistries.ITEM.get(loc);
    if (item == Items.AIR) return ItemStack.EMPTY;
    return new ItemStack(item, Math.max(1, count));
  }

  private static boolean hasPackChannel(Minecraft mc) {
    return mc.getConnection() != null
        && mc.getConnection().hasChannel(CubeyNetwork.C2SPackActionPayload.TYPE);
  }

  /**
   * Detects whether the Vanilla Recipe Book, Curios, or another mod is currently occupying the left
   * margin beside {@link InventoryScreen}. When true, the Cubey sidebar gracefully collapses so it
   * never overlaps or shifts the player inventory.
   */
  private static boolean isLeftMarginOccupied(InventoryScreen inv) {
    if (inv.getRecipeBookComponent().isVisible()) return true;
    int left = inv.getGuiLeft();
    if (left < SIDEBAR_W + 10) return true;
    int panelX0 = left - SIDEBAR_W - 6;
    int panelX1 = left - 2;
    int panelY0 = inv.getGuiTop() + 4;
    int panelY1 = inv.getGuiTop() + SIDEBAR_H + 10;
    for (var child : inv.children()) {
      if (child instanceof AbstractWidget w && w.visible) {
        int wx0 = w.getX();
        int wy0 = w.getY();
        int wx1 = wx0 + w.getWidth();
        int wy1 = wy0 + w.getHeight();
        if (wx1 > panelX0 && wx0 < panelX1 && wy1 > panelY0 && wy0 < panelY1) {
          return true;
        }
      }
    }
    return false;
  }

  public static void onScreenRender(ScreenEvent.Render.Post event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !hasPackChannel(mc)) return;

    if (event.getScreen() instanceof InventoryScreen inv) {
      renderInventoryAttachment(mc, inv, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    } else if (event.getScreen() instanceof ContainerScreen cs
        && CubeyPackMenu.MENU_TITLE.equals(cs.getTitle().getString())) {
      renderPackContainerDecor(mc, cs, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }
  }

  public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
    if (event.getButton() != 0) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !hasPackChannel(mc)) return;
    double mx = event.getMouseX();
    double my = event.getMouseY();

    if (event.getScreen() instanceof InventoryScreen inv) {
      boolean collapsed = isLeftMarginOccupied(inv);
      if (collapsed) {
        int bx = inv.getGuiLeft();
        int by = inv.getGuiTop() - 20;
        if (mx >= bx && mx <= bx + 44 && my >= by && my <= by + 17) {
          openPack(mc);
          event.setCanceled(true);
        }
      } else {
        int px = inv.getGuiLeft() - SIDEBAR_W - 4;
        int py = inv.getGuiTop() + 6;
        if (mx >= px && mx <= px + SIDEBAR_W && my >= py && my <= py + SIDEBAR_H) {
          openPack(mc);
          event.setCanceled(true);
        }
      }
    } else if (event.getScreen() instanceof ContainerScreen cs
        && CubeyPackMenu.MENU_TITLE.equals(cs.getTitle().getString())) {
      int gx = cs.getGuiLeft();
      int gy = cs.getGuiTop() - 21;
      int[] bits = {
        CubeyPackStorage.FLAG_HARVEST,
        CubeyPackStorage.FLAG_CRAFT,
        CubeyPackStorage.FLAG_TORCH,
        CubeyPackStorage.FLAG_COMBAT
      };
      int pillW = 43;
      for (int i = 0; i < bits.length; i++) {
        int px = gx + i * (pillW + 1);
        if (mx >= px && mx <= px + pillW && my >= gy && my <= gy + 18) {
          int nextFlags = syncedPack.flags() ^ bits[i];
          PacketDistributor.sendToServer(
              new CubeyNetwork.C2SPackActionPayload("toggle_flags", nextFlags));
          mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.15f));
          event.setCanceled(true);
          return;
        }
      }
    }
  }

  private static void openPack(Minecraft mc) {
    PacketDistributor.sendToServer(new CubeyNetwork.C2SPackActionPayload("open", 0));
    mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
  }

  private static void renderInventoryAttachment(
      Minecraft mc, InventoryScreen inv, GuiGraphics gg, int mouseX, int mouseY) {
    boolean collapsed = isLeftMarginOccupied(inv);
    if (collapsed) {
      int bx = inv.getGuiLeft();
      int by = inv.getGuiTop() - 20;
      boolean hover = mouseX >= bx && mouseX <= bx + 44 && mouseY >= by && mouseY <= by + 17;
      gg.fill(bx, by, bx + 44, by + 17, hover ? 0xEE183344 : 0xDD101C28);
      gg.renderOutline(bx, by, 44, 17, hover ? 0xFF66EEFF : 0xFF3A7B99);
      gg.drawCenteredString(mc.font, "✦ Pack", bx + 22, by + 5, hover ? 0xFFFFFF : 0xB8EEFF);
      return;
    }

    int px = inv.getGuiLeft() - SIDEBAR_W - 4;
    int py = inv.getGuiTop() + 6;
    boolean hover =
        mouseX >= px && mouseX <= px + SIDEBAR_W && mouseY >= py && mouseY <= py + SIDEBAR_H;

    // Sleek dark-glass sidebar panel attached cleanly to the left without moving InventoryScreen
    gg.fill(px, py, px + SIDEBAR_W, py + SIDEBAR_H, 0xDF0E1722);
    gg.renderOutline(px, py, SIDEBAR_W, SIDEBAR_H, hover ? 0xFF66EEFF : 0xFF315A72);
    gg.drawCenteredString(mc.font, "Cubey", px + SIDEBAR_W / 2, py + 4, 0x9CEBFF);

    // 1. Separate Dedicated Equipment Slot (Gold accent)
    int eqX = px + (SIDEBAR_W - 18) / 2;
    int eqY = py + 16;
    gg.fill(eqX, eqY, eqX + 18, eqY + 18, 0xCC1E1A10);
    gg.renderOutline(eqX, eqY, 18, 18, 0xFFE8B84B);
    ItemStack eq = resolveItem(syncedPack.equipItemId(), syncedPack.equipCount());
    if (!eq.isEmpty()) {
      gg.renderItem(eq, eqX + 1, eqY + 1);
      gg.renderItemDecorations(mc.font, eq, eqX + 1, eqY + 1);
    } else {
      gg.drawCenteredString(mc.font, "⚔", eqX + 9, eqY + 5, 0x77E8B84B);
    }

    // Subtle divider between Equipment and 6-slot Backpack
    gg.fill(px + 6, py + 38, px + SIDEBAR_W - 6, py + 39, 0x66407A96);

    // 2. Separate 6-Slot Backpack Grid (2 cols × 3 rows, Cyan accent)
    int gridX0 = px + (SIDEBAR_W - 38) / 2;
    int gridY0 = py + 43;
    for (int i = 0; i < CubeyPackStorage.BACKPACK_SIZE; i++) {
      int col = i % 2;
      int row = i / 2;
      int sx = gridX0 + col * 20;
      int sy = gridY0 + row * 20;
      gg.fill(sx, sy, sx + 18, sy + 18, 0xCC101E2B);
      gg.renderOutline(sx, sy, 18, 18, 0xAA3A85A8);
      String id = i < syncedPack.slotItemIds().size() ? syncedPack.slotItemIds().get(i) : "";
      int cnt = i < syncedPack.slotCounts().size() ? syncedPack.slotCounts().get(i) : 0;
      ItemStack st = resolveItem(id, cnt);
      if (!st.isEmpty()) {
        gg.renderItem(st, sx + 1, sy + 1);
        gg.renderItemDecorations(mc.font, st, sx + 1, sy + 1);
      }
    }

    gg.drawCenteredString(
        mc.font, hover ? "Öffnen ▶" : "Rucksack", px + SIDEBAR_W / 2, py + SIDEBAR_H - 11, hover ? 0xFFFFFF : 0x88B4C8);
  }

  private static void renderPackContainerDecor(
      Minecraft mc, ContainerScreen cs, GuiGraphics gg, int mouseX, int mouseY) {
    int gx = cs.getGuiLeft();
    int gy = cs.getGuiTop();
    int slotY = gy + 17;

    // Slot 0: Dedicated Equipment Slot (Gold border)
    int eqX = gx + 7;
    gg.renderOutline(eqX, slotY, 18, 18, 0xFFE8B84B);

    // Slots 1..6: 6-Slot Backpack (Cyan border)
    gg.renderOutline(gx + 7 + 18, slotY, 18 * 6, 18, 0xCC48D6FF);

    // Slots 7..8: Locked spacers (dimmed)
    for (int s = 7; s <= 8; s++) {
      int lx = gx + 7 + s * 18;
      gg.fill(lx + 1, slotY + 1, lx + 17, slotY + 17, 0xBB10141A);
      gg.drawCenteredString(mc.font, "×", lx + 9, slotY + 5, 0x66AAAAAA);
    }

    // Compact legend above slots
    gg.drawString(mc.font, "⚔", eqX + 5, gy + 6, 0xFFE8B84B, false);

    // 4 Clickable Helper Mode Toggle Pills right above the chest header
    int barY = gy - 21;
    int pillW = 43;
    String[] labels = {"Ernte", "Craft", "Licht", "Kampf"};
    int[] bits = {
      CubeyPackStorage.FLAG_HARVEST,
      CubeyPackStorage.FLAG_CRAFT,
      CubeyPackStorage.FLAG_TORCH,
      CubeyPackStorage.FLAG_COMBAT
    };
    for (int i = 0; i < labels.length; i++) {
      int px = gx + i * (pillW + 1);
      boolean on = (syncedPack.flags() & bits[i]) != 0;
      boolean hover = mouseX >= px && mouseX <= px + pillW && mouseY >= barY && mouseY <= barY + 18;
      int bg = on ? (hover ? 0xEE1A4B42 : 0xDD123832) : (hover ? 0xEE2B2228 : 0xDD1A181E);
      int border = on ? 0xFF4CF0B8 : 0xFF666677;
      gg.fill(px, barY, px + pillW, barY + 18, bg);
      gg.renderOutline(px, barY, pillW, 18, border);
      gg.drawCenteredString(
          mc.font, (on ? "● " : "○ ") + labels[i], px + pillW / 2, barY + 5, on ? 0xE8FFF5 : 0x9999AA);
    }
  }
}
