package dev.qb2.ui.screens;

import dev.qb2.client.lifecycle.ClientController;
import dev.qb2.companion.movement.Point;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Sleek Sci-Fi radial quick-question menu. Opened by holding the Look Here key (default: G) or
 * optionally when setting Cubey down onto a block. Hover over a slice and release the key (or click)
 * to dispatch the selected prompt!
 */
public final class RadialQuestionScreen extends Screen {
  public record Option(String label, String prompt, boolean bookmarkOnly) {}

  public static final List<Option> OPTIONS =
      List.of(
          new Option(
              "🔍 Was ist das?",
              "Erkläre mir kurz und verständlich, was dieser Block/diese Maschine ist und wofür man sie nutzt.",
              false),
          new Option(
              "⚙️ Wie baut man das?",
              "Wie craftet oder baut man das (nutze lookup_recipe falls verfügbar) und welche Ressourcen braucht man?",
              false),
          new Option(
              "💡 Ist das nützlich?",
              "Lohnt sich das für meinen aktuellen Fortschritt/meine Base und hast du praktische Tipps dazu?",
              false),
          new Option(
              "🔗 Aufbau erklären",
              "Erkläre, wie dieser Block mit den benachbarten Maschinen und Blöcken zusammenspielt.",
              false),
          new Option(
              "😄 Erzähl nen Witz!",
              "Erzähl mir einen kurzen, wirklich lustigen Minecraft- oder Technik-Witz passend zu diesem Ort!",
              false),
          new Option("📍 Ort merken", "", true));

  private final ClientController controller;
  private final String targetName;
  private final String targetContext;
  private final Point targetPos;
  private final KeyMapping holdKey;
  private int hoveredIndex = -1;

  public RadialQuestionScreen(
      ClientController controller,
      String targetName,
      String targetContext,
      Point targetPos,
      KeyMapping holdKey) {
    super(Component.literal("Cubey Quick Questions"));
    this.controller = controller;
    this.targetName = targetName == null || targetName.isBlank() ? "Umgebung" : targetName;
    this.targetContext = targetContext == null ? "" : targetContext;
    this.targetPos = targetPos;
    this.holdKey = holdKey;
  }

  @Override
  public void tick() {
    super.tick();
    // If opened by holding the Look Here key, releasing the key confirms the hovered slice!
    if (holdKey != null && !holdKey.isDown()) {
      confirmAndClose();
    }
  }

  private void confirmAndClose() {
    int chosen = hoveredIndex;
    onClose();
    if (chosen >= 0 && chosen < OPTIONS.size()) {
      controller.executeRadialQuestion(OPTIONS.get(chosen), targetName, targetContext, targetPos);
    }
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    updateHover(mouseX, mouseY);
    if (button == 0) {
      confirmAndClose();
      return true;
    }
    if (button == 1) {
      onClose();
      return true;
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  private void updateHover(double mouseX, double mouseY) {
    double cx = width / 2.0;
    double cy = height / 2.0;
    double dx = mouseX - cx;
    double dy = mouseY - cy;
    double dist = Math.hypot(dx, dy);
    if (dist < 24.0) {
      hoveredIndex = -1;
      return;
    }
    double ang = Math.atan2(dy, dx); // -PI..PI
    // Rotate so slice 0 is centered at top (-PI/2)
    double shifted = (ang + Math.PI * 2.5 + Math.PI / OPTIONS.size()) % (Math.PI * 2.0);
    hoveredIndex = (int) Math.floor(shifted / (Math.PI * 2.0 / OPTIONS.size())) % OPTIONS.size();
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    updateHover(mouseX, mouseY);
    int cx = width / 2;
    int cy = height / 2;

    // Subtle dark center hub showing target name
    String centerLabel = font.plainSubstrByWidth(targetName, 110);
    int cw = Math.max(54, font.width(centerLabel) / 2 + 10);
    g.fill(cx - cw, cy - 16, cx + cw, cy + 16, 0xCC0B1624);
    g.renderOutline(cx - cw, cy - 16, cw * 2, 32, 0xCC44CCFF);
    g.drawCenteredString(font, centerLabel, cx, cy - 9, 0xFFE0F7FF);
    g.drawCenteredString(
        font,
        hoveredIndex >= 0 ? "Loslassen / Klick" : "Abbrechen",
        cx,
        cy + 3,
        0xFF88BCD8);

    double radiusX = 112.0;
    double radiusY = 74.0;
    for (int i = 0; i < OPTIONS.size(); i++) {
      double angle = -Math.PI / 2.0 + i * (Math.PI * 2.0 / OPTIONS.size());
      int bx = cx + (int) Math.round(Math.cos(angle) * radiusX);
      int by = cy + (int) Math.round(Math.sin(angle) * radiusY);
      String label = OPTIONS.get(i).label();
      int hw = Math.max(52, font.width(label) / 2 + 10);
      int hh = 13;
      boolean hot = (i == hoveredIndex);
      int bg = hot ? 0xE6143A54 : 0xBB0C1826;
      int border = hot ? 0xFF55E6FF : 0x99386488;
      int textCol = hot ? 0xFFFFFFFF : 0xFFBEE4FF;
      g.fill(bx - hw, by - hh, bx + hw, by + hh, bg);
      g.renderOutline(bx - hw, by - hh, hw * 2, hh * 2, border);
      g.drawCenteredString(font, label, bx, by - 4, textCol);
    }
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
