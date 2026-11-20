package dev.qb2.ui.overlay;

import dev.qb2.network.CubeyNetwork;
import dev.qb2.server.CubeyPackMenu;
import dev.qb2.server.CubeyPackStorage;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Top-docked, EMI/JEI/Curios-safe inline Cubey Pack bar directly above {@link InventoryScreen}
 * (matching its exact 176px width so left/right recipe & Curios sidebars are NEVER touched!). All 7
 * slots (1 Gold Equipment Slot + 6 Cyan Backpack Slots) are directly interactive (LMB swap/merge,
 * RMB split/place-one, Shift-Click quick-move) with zero separate screen required.
 */
public final class CubeyInventoryOverlay {
  private static volatile CubeyNetwork.S2CPackSyncPayload syncedPack =
      new CubeyNetwork.S2CPackSyncPayload(
          ItemStack.EMPTY,
          List.of(
              ItemStack.EMPTY,
              ItemStack.EMPTY,
              ItemStack.EMPTY,
              ItemStack.EMPTY,
              ItemStack.EMPTY,
              ItemStack.EMPTY),
          CubeyPackStorage.DEFAULT_FLAGS);

  private static final int BAR_W = 176;
  private static final int BAR_H = 26;

  private static final int[] FLAG_BITS = {
    CubeyPackStorage.FLAG_HARVEST,
    CubeyPackStorage.FLAG_CRAFT,
    CubeyPackStorage.FLAG_TORCH,
    CubeyPackStorage.FLAG_COMBAT
  };
  private static final String[] FLAG_SHORT = {"E", "C", "L", "K"};
  private static final String[] FLAG_NAMES = {
    "Ernten (bei Laune)", "Craften & Kochen", "Fackel-Assistent", "Kampf-Support"
  };

  private CubeyInventoryOverlay() {}

  public static void updateSync(CubeyNetwork.S2CPackSyncPayload payload) {
    if (payload != null) syncedPack = payload;
  }

  public static void reset() {
    syncedPack =
        new CubeyNetwork.S2CPackSyncPayload(
            ItemStack.EMPTY,
            List.of(
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY,
                ItemStack.EMPTY),
            CubeyPackStorage.DEFAULT_FLAGS);
  }

  public static ItemStack equippedStack() {
    ItemStack eq = syncedPack.equipStack();
    return eq == null ? ItemStack.EMPTY : eq;
  }

  public static String equippedItemId() {
    ItemStack eq = equippedStack();
    return eq.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(eq.getItem()).toString();
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
        && mc.getConnection().hasChannel(CubeyNetwork.C2SPackSlotClickPayload.TYPE);
  }

  private static int barX(InventoryScreen inv) {
    return inv.getGuiLeft();
  }

  /** Docks directly ABOVE InventoryScreen (zero EMI/JEI/Curios overlap); falls back below if tiny. */
  private static int barY(InventoryScreen inv) {
    int top = inv.getGuiTop();
    return top >= BAR_H + 4 ? (top - BAR_H - 3) : (top + inv.getYSize() + 3);
  }

  private static int slotX(int barX, int slotIndex) {
    if (slotIndex == 0) return barX + 4;
    return barX + 27 + (slotIndex - 1) * 19;
  }

  private static ItemStack stackForSlot(int slotIndex) {
    if (slotIndex == 0) {
      return equippedStack();
    }
    int idx = slotIndex - 1;
    var list = syncedPack.backpackStacks();
    if (list == null || idx < 0 || idx >= list.size()) return ItemStack.EMPTY;
    ItemStack st = list.get(idx);
    return st == null ? ItemStack.EMPTY : st;
  }

  public static void onScreenRender(ScreenEvent.Render.Post event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !hasPackChannel(mc)) return;

    if (event.getScreen() instanceof InventoryScreen inv) {
      renderTopDockedBar(mc, inv, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    } else if (event.getScreen() instanceof CreativeModeInventoryScreen cis) {
      int bx = cis.getGuiLeft() + cis.getXSize() - 48;
      int by = Math.max(2, cis.getGuiTop() - 48);
      int mx = event.getMouseX();
      int my = event.getMouseY();
      boolean hover = mx >= bx && mx <= bx + 46 && my >= by && my <= by + 16;
      var gg = event.getGuiGraphics();
      gg.fill(bx, by, bx + 46, by + 16, hover ? 0xEE183344 : 0xDD101C28);
      gg.renderOutline(bx, by, 46, 16, hover ? 0xFF66EEFF : 0xFF3A7B99);
      gg.drawCenteredString(mc.font, "✦ Pack", bx + 23, by + 4, hover ? 0xFFFFFF : 0xB8EEFF);
    } else if (event.getScreen() instanceof ContainerScreen cs
        && CubeyPackMenu.MENU_TITLE.equals(cs.getTitle().getString())) {
      renderPackContainerDecor(mc, cs, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }
  }

  public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
    int button = event.getButton();
    if (button != 0 && button != 1) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.player == null || !hasPackChannel(mc)) return;
    double mx = event.getMouseX();
    double my = event.getMouseY();

    if (event.getScreen() instanceof InventoryScreen inv) {
      int bx = barX(inv);
      int by = barY(inv);
      if (mx < bx || mx > bx + BAR_W || my < by || my > by + BAR_H) return;

      // 1. Direct interactive slot clicks (0 = Equipment, 1..6 = 6 Backpack Slots)
      int sy = by + 4;
      for (int s = 0; s < CubeyPackStorage.TOTAL_SLOTS; s++) {
        int sx = slotX(bx, s);
        if (mx >= sx && mx <= sx + 18 && my >= sy && my <= sy + 18) {
          PacketDistributor.sendToServer(
              new CubeyNetwork.C2SPackSlotClickPayload(s, button, Screen.hasShiftDown()));
          mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.25f));
          event.setCanceled(true);
          return;
        }
      }

      // 2. Compact 2×2 Helper Mode Toggles on the right side of the bar
      if (button == 0) {
        int fx0 = bx + 144;
        int fy0 = by + 3;
        for (int i = 0; i < 4; i++) {
          int px = fx0 + (i % 2) * 14;
          int py = fy0 + (i / 2) * 10;
          if (mx >= px && mx <= px + 13 && my >= py && my <= py + 9) {
            int nextFlags = syncedPack.flags() ^ FLAG_BITS[i];
            PacketDistributor.sendToServer(
                new CubeyNetwork.C2SPackActionPayload("toggle_flags", nextFlags));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.15f));
            event.setCanceled(true);
            return;
          }
        }
      }
      // Consume stray clicks inside bar background so carried items are never accidentally dropped
      event.setCanceled(true);
    } else if (button == 0 && event.getScreen() instanceof CreativeModeInventoryScreen cis) {
      int bx = cis.getGuiLeft() + cis.getXSize() - 48;
      int by = Math.max(2, cis.getGuiTop() - 48);
      if (mx >= bx && mx <= bx + 46 && my >= by && my <= by + 16) {
        PacketDistributor.sendToServer(new CubeyNetwork.C2SPackActionPayload("open", 0));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
        event.setCanceled(true);
      }
    } else if (button == 0
        && event.getScreen() instanceof ContainerScreen cs
        && CubeyPackMenu.MENU_TITLE.equals(cs.getTitle().getString())) {
      int gx = cs.getGuiLeft();
      int gy = cs.getGuiTop() - 21;
      int pillW = 43;
      for (int i = 0; i < FLAG_BITS.length; i++) {
        int px = gx + i * (pillW + 1);
        if (mx >= px && mx <= px + pillW && my >= gy && my <= gy + 18) {
          int nextFlags = syncedPack.flags() ^ FLAG_BITS[i];
          PacketDistributor.sendToServer(
              new CubeyNetwork.C2SPackActionPayload("toggle_flags", nextFlags));
          mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.15f));
          event.setCanceled(true);
          return;
        }
      }
    }
  }

  private static void renderTopDockedBar(
      Minecraft mc, InventoryScreen inv, GuiGraphics gg, int mouseX, int mouseY) {
    int bx = barX(inv);
    int by = barY(inv);

    // Sleek dark-glass bar matching InventoryScreen's exact 176px width (zero EMI/Curios collision!)
    gg.fill(bx, by, bx + BAR_W, by + BAR_H, 0xE20E1722);
    gg.renderOutline(bx, by, BAR_W, BAR_H, 0xFF346782);

    int sy = by + 4;
    ItemStack hoveredStack = ItemStack.EMPTY;
    Component hoveredHint = null;

    // Slot 0: Dedicated Equipment Slot (Gold frame, clearly separated from 6 Backpack Slots)
    int eqX = slotX(bx, 0);
    boolean hoverEq = mouseX >= eqX && mouseX <= eqX + 18 && mouseY >= sy && mouseY <= sy + 18;
    gg.fill(eqX, sy, eqX + 18, sy + 18, hoverEq ? 0xDD352C16 : 0xCC1E1A10);
    gg.renderOutline(eqX, sy, 18, 18, hoverEq ? 0xFFFFDA75 : 0xFFE8B84B);
    ItemStack eq = stackForSlot(0);
    if (!eq.isEmpty()) {
      gg.renderItem(eq, eqX + 1, sy + 1);
      gg.renderItemDecorations(mc.font, eq, eqX + 1, sy + 1);
      if (hoverEq) hoveredStack = eq;
    } else {
      gg.drawCenteredString(mc.font, "⚔", eqX + 9, sy + 5, 0x88E8B84B);
      if (hoverEq) {
        hoveredHint =
            Component.literal(
                "Cubey Ausrüstung: Schwert/Axt, Bogen/Armbrust, Schild, Heiltrank/Goldapfel, Laterne, Amethyst, Glowstone");
      }
    }

    // Crisp vertical divider separating Equipment from 6-Slot Backpack
    gg.fill(bx + 24, by + 4, bx + 25, by + BAR_H - 4, 0x885296B5);

    // Slots 1..6: 6 Backpack Slots (Cyan frame, directly clickable!)
    for (int s = 1; s < CubeyPackStorage.TOTAL_SLOTS; s++) {
      int sx = slotX(bx, s);
      boolean hover = mouseX >= sx && mouseX <= sx + 18 && mouseY >= sy && mouseY <= sy + 18;
      gg.fill(sx, sy, sx + 18, sy + 18, hover ? 0xDD1C354A : 0xCC101E2B);
      gg.renderOutline(sx, sy, 18, 18, hover ? 0xFF7BE8FF : 0xAA3A85A8);
      ItemStack st = stackForSlot(s);
      if (!st.isEmpty()) {
        gg.renderItem(st, sx + 1, sy + 1);
        gg.renderItemDecorations(mc.font, st, sx + 1, sy + 1);
        if (hover) hoveredStack = st;
      } else if (hover) {
        hoveredHint = Component.literal("Cubey Rucksack Slot " + s + " (LMB/RMB/Shift)");
      }
    }

    // Compact 2×2 Helper Mode Toggle buttons on the right end (bx + 144 .. bx + 172)
    int fx0 = bx + 144;
    int fy0 = by + 3;
    for (int i = 0; i < 4; i++) {
      int px = fx0 + (i % 2) * 14;
      int py = fy0 + (i / 2) * 10;
      boolean on = (syncedPack.flags() & FLAG_BITS[i]) != 0;
      boolean hover = mouseX >= px && mouseX <= px + 13 && mouseY >= py && mouseY <= py + 9;
      int bg = on ? (hover ? 0xEE1F6152 : 0xDD14453B) : (hover ? 0xEE352A30 : 0xDD1B1A22);
      int border = on ? 0xFF4CF0B8 : 0xFF5A5F70;
      gg.fill(px, py, px + 13, py + 9, bg);
      gg.renderOutline(px, py, 13, 9, border);
      gg.drawCenteredString(mc.font, FLAG_SHORT[i], px + 7, py + 1, on ? 0xE8FFF5 : 0x88889A);
      if (hover) {
        hoveredHint = Component.literal(FLAG_NAMES[i] + ": " + (on ? "AN" : "AUS"));
      }
    }

    // Render hover tooltip cleanly when cursor isn't carrying an item, OR re-draw carried item ON TOP!
    ItemStack carried = mc.player.containerMenu.getCarried();
    if (carried.isEmpty()) {
      if (!hoveredStack.isEmpty()) {
        gg.renderTooltip(mc.font, hoveredStack, mouseX, mouseY);
      } else if (hoveredHint != null) {
        gg.renderTooltip(mc.font, hoveredHint, mouseX, mouseY);
      }
    } else if (mouseX >= bx - 10
        && mouseX <= bx + BAR_W + 10
        && mouseY >= by - 10
        && mouseY <= by + BAR_H + 10) {
      gg.pose().pushPose();
      gg.pose().translate(0f, 0f, 260f);
      gg.renderItem(carried, mouseX - 8, mouseY - 8);
      gg.renderItemDecorations(mc.font, carried, mouseX - 8, mouseY - 8);
      gg.pose().popPose();
    }
  }

  private static void renderPackContainerDecor(
      Minecraft mc, ContainerScreen cs, GuiGraphics gg, int mouseX, int mouseY) {
    int gx = cs.getGuiLeft();
    int gy = cs.getGuiTop();
    int slotY = gy + 17;

    gg.renderOutline(gx + 7, slotY, 18, 18, 0xFFE8B84B);
    gg.renderOutline(gx + 7 + 18, slotY, 18 * 6, 18, 0xCC48D6FF);
    for (int s = 7; s <= 8; s++) {
      int lx = gx + 7 + s * 18;
      gg.fill(lx + 1, slotY + 1, lx + 17, slotY + 17, 0xBB10141A);
      gg.drawCenteredString(mc.font, "×", lx + 9, slotY + 5, 0x66AAAAAA);
    }

    int barY = gy - 21;
    int pillW = 43;
    String[] labels = {"Ernte", "Craft", "Licht", "Kampf"};
    for (int i = 0; i < labels.length; i++) {
      int px = gx + i * (pillW + 1);
      boolean on = (syncedPack.flags() & FLAG_BITS[i]) != 0;
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
