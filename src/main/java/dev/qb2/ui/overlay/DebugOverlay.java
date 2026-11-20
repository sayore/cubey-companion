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
    if (!settings.debug || session == null) return;
    var mc = Minecraft.getInstance();
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
