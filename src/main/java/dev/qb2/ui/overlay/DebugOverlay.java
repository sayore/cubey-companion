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
    // Live Push-to-Talk Whisper Microphone HUD Pill (top-center / above hotbar)
    if (controller.voice.isRecording() || controller.voice.isTranscribing()) {
      int sw = mc.getWindow().getGuiScaledWidth();
      int sh = mc.getWindow().getGuiScaledHeight();
      int cx = sw / 2;
      int cy = sh - 78;
      if (controller.voice.isRecording()) {
        float lvl = controller.voice.liveLevel();
        int bars = Math.round(lvl * 6);
        StringBuilder meter = new StringBuilder();
        for (int i = 0; i < 6; i++) meter.append(i < bars ? '█' : '░');
        String label =
            String.format(
                java.util.Locale.ROOT,
                "🎙 Cubey hört zu... [%s] %.1fs",
                meter,
                controller.voice.recordingSeconds());
        int tw = mc.font.width(label);
        event.getGuiGraphics().fill(cx - tw / 2 - 6, cy - 3, cx + tw / 2 + 6, cy + 11, 0xCC1E1028);
        event.getGuiGraphics().renderOutline(cx - tw / 2 - 6, cy - 3, tw + 12, 14, 0xEEFF5599);
        event.getGuiGraphics().drawString(mc.font, label, cx - tw / 2, cy, 0xFFFFD8EC);
      } else {
        String label = "🎙 Whisper transkribiert...";
        int tw = mc.font.width(label);
        event.getGuiGraphics().fill(cx - tw / 2 - 6, cy - 3, cx + tw / 2 + 6, cy + 11, 0xCC0D1E2E);
        event.getGuiGraphics().renderOutline(cx - tw / 2 - 6, cy - 3, tw + 12, 14, 0xEE44DDFF);
        event.getGuiGraphics().drawString(mc.font, label, cx - tw / 2, cy, 0xFFB8ECFF);
      }
    }
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
        String hint = "✦ Cubey gehalten · RMB halten: Werfen (Apportieren/BONK!) · LMB loslassen: Ziel anvisieren";
        int tw = mc.font.width(hint);
        event
            .getGuiGraphics()
            .fill(cx - tw / 2 - 5, cy - 2, cx + tw / 2 + 5, cy + 10, 0x990D1A26);
        event.getGuiGraphics().drawString(mc.font, hint, cx - tw / 2, cy, 0xFFB8ECFF);
      }
    }
    // Sneak + Looking at another player's Cubey -> compact quick-info card
    if (settings.multiplayerSync && mc.player != null && mc.player.isCrouching() && mc.screen == null) {
      var eye = dev.qb2.perception.world.WorldSensor.point(mc.player.getEyePosition());
      var look = dev.qb2.perception.world.WorldSensor.point(mc.player.getLookAngle());
      var hitPeer = session.peers.findRayHit(eye, look, 6.0, 0.42);
      if (hitPeer.isPresent()) {
        var peer = hitPeer.get();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int cx = sw / 2;
        int cy = sh / 2 + 16;
        String title = peer.cubeyName + " · von " + peer.ownerName;
        String sub = peer.state + " · Skin: " + peer.skin + " · RMB: ♥  LMB: ✦";
        int w = Math.max(mc.font.width(title), mc.font.width(sub)) + 12;
        int x0 = cx - w / 2;
        event.getGuiGraphics().fill(x0, cy - 3, x0 + w, cy + 21, 0xCC0D1824);
        event.getGuiGraphics().renderOutline(x0, cy - 3, w, 24, 0xCC48D6FF);
        event.getGuiGraphics().drawCenteredString(mc.font, title, cx, cy, 0xFFE6FAFF);
        event.getGuiGraphics().drawCenteredString(mc.font, sub, cx, cy + 10, 0xFF9AC8DA);
      }
    }
    // Feature #10: Create Wrench Assistant (live RPM / Overstressed / Blaze Burner heat!)
    if (mc.player != null
        && mc.level != null
        && mc.screen == null
        && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) {
      var held = mc.player.getMainHandItem();
      String heldId =
          held.isEmpty()
              ? ""
              : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
      if (heldId.contains("wrench")) {
        var be = mc.level.getBlockEntity(bhr.getBlockPos());
        if (be != null) {
          try {
            var tag = be.saveWithoutMetadata(mc.level.registryAccess());
            String info = "";
            int col = 0xFF9CEBFF;
            if (tag.contains("OverStressed") && tag.getBoolean("OverStressed")) {
              info = "⚠ ÜBERLASTET (Overstressed!)";
              col = 0xFFFF6666;
            } else if (tag.contains("Speed")) {
              float rpm = tag.getFloat("Speed");
              info =
                  Math.abs(rpm) < 0.5f
                      ? "⚙ 0 RPM (kein Antrieb)"
                      : String.format(java.util.Locale.ROOT, "⚙ %.0f RPM (%s)", Math.abs(rpm), rpm > 0 ? "↻" : "↺");
              col = Math.abs(rpm) < 0.5f ? 0xFFFFB855 : 0xFF55F5B0;
            } else if (tag.contains("fuelLevel")) {
              int fl = tag.getInt("fuelLevel");
              info = fl <= 0 ? "🔥 Blaze Burner: Kalt" : "🔥 Blaze Burner: Aktiv (" + fl + ")";
              col = fl <= 0 ? 0xFFFFAA55 : 0xFFFFD866;
            }
            if (!info.isBlank()) {
              int sw = mc.getWindow().getGuiScaledWidth();
              int sh = mc.getWindow().getGuiScaledHeight();
              int cx = sw / 2;
              int cy = sh / 2 + 16;
              int tw = mc.font.width(info) + 10;
              event.getGuiGraphics().fill(cx - tw / 2, cy - 2, cx + tw / 2, cy + 11, 0xCC0D1824);
              event.getGuiGraphics().renderOutline(cx - tw / 2, cy - 2, tw, 13, col);
              event.getGuiGraphics().drawCenteredString(mc.font, info, cx, cy, col);
            }
          } catch (Exception ignored) {
          }
        }
      }
    }
    // Feature #20: Compact Cubey Equipment Status Badge next to the hotbar
    if (mc.screen == null) {
      var eq = CubeyInventoryOverlay.equippedStack();
      if (!eq.isEmpty()) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int bx = sw / 2 + 98;
        int by = sh - 21;
        boolean pulse = session.combatActionTicks > 0;
        var gg = event.getGuiGraphics();
        gg.fill(bx, by, bx + 18, by + 18, pulse ? 0xDD3A2810 : 0xBB0D1622);
        gg.renderOutline(bx, by, 18, 18, pulse ? 0xFFFFE066 : 0xCCDBA842);
        gg.renderItem(eq, bx + 1, by + 1);
        gg.renderItemDecorations(mc.font, eq, bx + 1, by + 1);
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
