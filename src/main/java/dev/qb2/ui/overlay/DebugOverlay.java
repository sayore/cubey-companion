package dev.qb2.ui.overlay;

import dev.qb2.client.lifecycle.ClientController;
import dev.qb2.perception.world.ModpackInspector;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Compact debug HUD overlay for QB2 state, model/backoff status, NERVE hits, and catalog info. */
public final class DebugOverlay {
  private DebugOverlay() {}

  public static void render(RenderGuiEvent.Post event, ClientController controller) {
    var settings = controller.configuration.settings();
    var session = controller.session;
    if (session == null || settings.paused) return;
    var mc = Minecraft.getInstance();
    // Live Grab & Throw Charge Bar HUD right below the crosshair!
    if (session.grabThrow.isGrabbed()) {
      int sw = mc.getWindow().getGuiScaledWidth();
      int sh = mc.getWindow().getGuiScaledHeight();
      int cx = sw / 2;
      int cy = sh / 2 + 22;
      if (session.grabThrow.isCharging()) {
        float charge = session.grabThrow.chargeRatio();
        int barW = 104;
        int barH = 8;
        int x0 = cx - barW / 2;
        int fillW = Math.round(barW * charge);
        // Smooth color shift: cyan -> gold -> fiery orange as throw power approaches 100%
        int fillColor =
            charge < 0.5f ? 0xEE44DDFF : charge < 0.82f ? 0xEEFFD244 : 0xEEFF6B2B;
        event.getGuiGraphics().fill(x0 - 2, cy - 2, x0 + barW + 2, cy + barH + 2, 0xBB0B1420);
        event.getGuiGraphics().fill(x0, cy, x0 + barW, cy + barH, 0x881A2E44);
        if (fillW > 0) {
          event.getGuiGraphics().fill(x0, cy, x0 + fillW, cy + barH, fillColor);
        }
        String pct = "⚡ Wurfkraft " + Math.round(charge * 100f) + "%";
        int tw = mc.font.width(pct);
        event.getGuiGraphics().drawString(mc.font, pct, cx - tw / 2, cy + 12, fillColor);
      } else {
        String hint = "✦ Cubey gehalten · RMB halten zum Werfen · LMB loslassen zum Absetzen";
        int tw = mc.font.width(hint);
        event
            .getGuiGraphics()
            .fill(cx - tw / 2 - 5, cy - 2, cx + tw / 2 + 5, cy + 10, 0x990D1A26);
        event.getGuiGraphics().drawString(mc.font, hint, cx - tw / 2, cy, 0xFFB8ECFF);
      }
    }
    // Subtle bottom-center HUD thought subtitle so you never have to spin the camera to read thoughts.
    if (!session.isSleeping()) {
      var thought = session.bubbles.activeThought(session.tick, settings.thoughts);
      if (thought != null && !thought.text().isBlank() && !thought.text().equals("z z z")) {
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int alpha = (int) (220 * thought.opacity(session.tick, partial));
        if (alpha > 16) {
          int sw = mc.getWindow().getGuiScaledWidth();
          int sh = mc.getWindow().getGuiScaledHeight();
          String text =
              mc.font.plainSubstrByWidth(
                  "◦ " + thought.text().replace('\n', ' '), Math.max(120, sw - 60));
          int tw = mc.font.width(text);
          int x = (sw - tw) / 2;
          int y = sh - 58;
          int bg = ((alpha * 3 / 5) << 24) | 0x0D1A26;
          int fg = (alpha << 24) | 0xB8E2FF;
          event.getGuiGraphics().fill(x - 5, y - 2, x + tw + 5, y + 10, bg);
          event.getGuiGraphics().drawString(mc.font, text, x, y, fg);
        }
      }
    }
    if (!settings.debug) return;
    var mind = controller.mind;
    String modelTag = mind.activeModel.isBlank() ? settings.model : mind.activeModel;
    String cooldownTag =
        mind.inCooldown() ? " · backoff " + mind.cooldownRemainingSeconds() + "s" : "";
    var viewerMods = ModpackInspector.detectedViewerMods();
    String modpackTag = viewerMods.isEmpty() ? "" : " · mods:" + String.join(",", viewerMods);
    var lines =
        List.of(
            "QB2 · "
                + session.state
                + " · "
                + (settings.paused ? "PAUSED" : mind.status)
                + cooldownTag,
            "Model: " + modelTag + modpackTag,
            "Target: "
                + (session.attention.target() == null ? "none" : session.attention.target().key()),
            "Seen: " + session.observations.size() + " | action: " + controller.lastAction,
            "Keywords: " + String.join(", ", controller.keywords),
            "NERVE: "
                + mind.memoryStatus
                + " · hits "
                + controller.recalled.stream()
                    .limit(3)
                    .map(e -> e.kind() + ":" + e.id().substring(0, 8))
                    .collect(Collectors.joining(","))
                + " · pending "
                + mind.pending,
            "Context ~"
                + controller.contextTokens
                + " tokens · "
                + controller.inferenceMillis
                + "ms · requests "
                + controller.pendingTriggerCount(),
            "Knowledge: " + controller.knowledge.status + " · recalled " + mind.knowledgeHits,
            "Error: " + controller.error);
    int y = 8;
    int maxWidth = Math.max(100, mc.getWindow().getGuiScaledWidth() - 16);
    for (String line : lines) {
      String clipped = mc.font.plainSubstrByWidth(line, maxWidth);
      event.getGuiGraphics().drawString(mc.font, clipped, 8, y, 0xC8EFFF);
      y += 11;
    }
  }
}
