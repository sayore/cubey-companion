package dev.qb2.rendering.bubbles;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.qb2.client.config.Settings;
import dev.qb2.client.lifecycle.CompanionSession;
import dev.qb2.companion.movement.Point;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class BubbleRenderer {
  public void render(
      RenderLevelStageEvent event, CompanionSession session, Settings settings, Point position) {
    var mc = Minecraft.getInstance();
    var bubble = session.bubbles.visible(session.tick, settings.thoughts, settings.speech);
    var camera = event.getCamera().getPosition();
    double distance = position.distance(new Point(camera.x, camera.y, camera.z));
    if (bubble == null || distance < 0.5) return;
    var lines =
        new ArrayList<>(
            mc.font.split(
                Component.literal((bubble.thought() ? "◦ " : "QB2: ") + bubble.text()), 160));
    if (lines.size() > 8) {
      lines.subList(8, lines.size()).clear();
      lines.set(7, Component.literal("…").getVisualOrderText());
    }
    int width = lines.stream().mapToInt(mc.font::width).max().orElse(1);
    var layout =
        BubbleLayout.calculate(
            distance,
            event.getProjectionMatrix().m00(),
            event.getProjectionMatrix().m11(),
            width,
            lines.size());
    PoseStack poses = new PoseStack();
    poses.translate(
        position.x() - camera.x, position.y() - camera.y + 0.3, position.z() - camera.z);
    poses.mulPose(event.getCamera().rotation());
    poses.scale(layout.scale(), -layout.scale(), layout.scale());
    float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
    int alpha = (int) (255 * bubble.opacity(session.tick, partial));
    if (alpha <= 4) return;
    var buffers = mc.renderBuffers().bufferSource();
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      float x = -mc.font.width(line) / 2f, y = (i - lines.size()) * 10;
      int color = (alpha << 24) | (bubble.thought() ? 0xC5DDEF : 0xFFFFFF);
      int background =
          ((int) (settings.bubbleOpacity * alpha) << 24) | (bubble.thought() ? 0x162433 : 0x102838);
      mc.font.drawInBatch(
          line,
          x,
          y,
          (Math.max(5, alpha / 4) << 24) | (color & 0xFFFFFF),
          false,
          poses.last().pose(),
          buffers,
          Font.DisplayMode.SEE_THROUGH,
          background,
          LightTexture.FULL_BRIGHT);
      buffers.endBatch();
      mc.font.drawInBatch(
          line,
          x,
          y,
          color,
          false,
          poses.last().pose(),
          buffers,
          Font.DisplayMode.NORMAL,
          0,
          LightTexture.FULL_BRIGHT);
    }
    buffers.endBatch();
  }
}
