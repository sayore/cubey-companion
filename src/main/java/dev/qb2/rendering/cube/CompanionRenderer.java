package dev.qb2.rendering.cube;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.qb2.client.config.Settings;
import dev.qb2.client.lifecycle.CompanionSession;
import dev.qb2.companion.movement.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class CompanionRenderer {
  private static final ResourceLocation WHITE =
      ResourceLocation.fromNamespaceAndPath("qb2", "textures/cube.png");
  private final RenderType type = RenderType.entityTranslucent(WHITE);
  private long lastFrame = System.nanoTime();
  private final dev.qb2.rendering.animation.CubeAnimation animation =
      new dev.qb2.rendering.animation.CubeAnimation();
  private final dev.qb2.rendering.bubbles.BubbleRenderer bubbles =
      new dev.qb2.rendering.bubbles.BubbleRenderer();

  public void render(RenderLevelStageEvent event, CompanionSession session, Settings settings) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
    var mc = Minecraft.getInstance();
    if (mc.level == null || mc.player == null) return;
    long now = System.nanoTime();
    double dt = Math.clamp((now - lastFrame) / 1e9, 0, 0.1);
    lastFrame = now;
    float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
    Point position = session.motion.interpolate(partial);
    Point look =
        session.attention.target() == null || session.bubbles.speaking(session.tick)
            ? new Point(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ())
            : session.attention.target().position();
    Point direction = look.subtract(position);
    float targetYaw = (float) Math.toDegrees(Math.atan2(direction.x(), direction.z()));
    double time = (session.tick + partial) * 0.05;
    float lookPitch =
        (float)
            -Math.toDegrees(Math.atan2(direction.y(), Math.hypot(direction.x(), direction.z())));
    var frame =
        animation.update(session.state, time, session.motion.speed(), dt, targetYaw, lookPitch);
    PoseStack poses = new PoseStack();
    // NeoForge 1.21.1 already applies the camera model-view on RenderSystem at this stage.
    var camera = event.getCamera().getPosition();
    poses.pushPose();
    poses.translate(
        position.x() - camera.x,
        position.y() - camera.y + Math.sin(time * 2) * 0.025,
        position.z() - camera.z);
    poses.mulPose(Axis.YP.rotationDegrees(frame.yaw()));
    poses.mulPose(Axis.ZP.rotationDegrees(frame.roll()));
    poses.mulPose(Axis.XP.rotationDegrees(frame.pitch()));
    var buffers = mc.renderBuffers().bufferSource();
    var vertices = buffers.getBuffer(type);
    box(
        poses, vertices, -0.125f, -0.125f, -0.125f, 0.125f, 0.125f, 0.125f, 0.035f, 0.045f, 0.060f,
        1);
    // Keep the sensor geometry in the same full-bright material pass as the body.
    // Separate eyes/emissive passes are discarded by some Iris/Sodium pack pipelines.
    var light = vertices;
    float intensity = (float) (0.45 + settings.glow * frame.light() * 0.55);
    float sr = session.apiBackoff ? intensity : intensity * 0.90f;
    float sg = session.apiBackoff ? intensity * 0.70f : intensity * 0.97f;
    float sb = session.apiBackoff ? intensity * 0.25f : intensity;
    // Four equal sensor tiles form a technical array, without eyes, mouth or facial animation.
    for (float x : new float[] {-0.064f, 0.064f})
      for (float y : new float[] {-0.064f, 0.064f})
        box(
            poses,
            light,
            x - 0.022f,
            y - 0.022f,
            0.1251f,
            x + 0.022f,
            y + 0.022f,
            0.126f,
            sr,
            sg,
            sb,
            1);
    buffers.endBatch(type);
    poses.popPose();
    if (settings.showTargetBeam
        && session.activeTarget() != null
        && java.util.Set.of("go_to", "inspect", "approach", "orbit").contains(session.action)) {
      renderTargetBeam(buffers, camera, position, session.activeTarget().position(), intensity);
    }
    bubbles.render(event, session, settings, position);
  }

  private void renderTargetBeam(
      MultiBufferSource.BufferSource buffers,
      net.minecraft.world.phys.Vec3 camera,
      Point from,
      Point to,
      float intensity) {
    double dist = from.distance(to);
    if (dist < 0.25 || dist > 18.0) return;
    PoseStack beamPose = new PoseStack();
    beamPose.pushPose();
    var lineBuf = buffers.getBuffer(RenderType.lines());
    float fx = (float) (from.x() - camera.x);
    float fy = (float) (from.y() - camera.y);
    float fz = (float) (from.z() - camera.z);
    float tx = (float) (to.x() - camera.x);
    float ty = (float) (to.y() - camera.y);
    float tz = (float) (to.z() - camera.z);
    float r = 0.55f * intensity, g = 0.90f * intensity, b = 1.0f * intensity, a = 0.55f;
    lineBuf.addVertex(beamPose.last(), fx, fy, fz).setColor(r, g, b, a).setNormal(beamPose.last(), 0, 1, 0);
    lineBuf.addVertex(beamPose.last(), tx, ty, tz).setColor(r, g, b, a * 0.5f).setNormal(beamPose.last(), 0, 1, 0);
    LevelRenderer.renderLineBox(
        beamPose,
        lineBuf,
        tx - 0.52,
        ty - 0.52,
        tz - 0.52,
        tx + 0.52,
        ty + 0.52,
        tz + 0.52,
        r,
        g,
        b,
        0.45f);
    buffers.endBatch(RenderType.lines());
    beamPose.popPose();
  }

  private static void box(
      PoseStack p,
      VertexConsumer v,
      float x0,
      float y0,
      float z0,
      float x1,
      float y1,
      float z1,
      float r,
      float g,
      float b,
      float a) {
    face(
        p,
        v,
        new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
        0,
        0,
        1,
        r,
        g,
        b,
        a);
    face(
        p,
        v,
        new float[][] {{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}},
        0,
        0,
        -1,
        r,
        g,
        b,
        a);
    face(
        p,
        v,
        new float[][] {{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}},
        1,
        0,
        0,
        r,
        g,
        b,
        a);
    face(
        p,
        v,
        new float[][] {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
        -1,
        0,
        0,
        r,
        g,
        b,
        a);
    face(
        p,
        v,
        new float[][] {{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}},
        0,
        1,
        0,
        r,
        g,
        b,
        a);
    face(
        p,
        v,
        new float[][] {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
        0,
        -1,
        0,
        r,
        g,
        b,
        a);
  }

  private static void face(
      PoseStack p,
      VertexConsumer v,
      float[][] points,
      float nx,
      float ny,
      float nz,
      float r,
      float g,
      float b,
      float a) {
    for (int i = 0; i < 4; i++)
      v.addVertex(p.last(), points[i][0], points[i][1], points[i][2])
          .setColor(r, g, b, a)
          .setUv(i == 1 || i == 2 ? 1 : 0, i >= 2 ? 0 : 1)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(LightTexture.FULL_BRIGHT)
          .setNormal(p.last(), nx, ny, nz);
  }
}
