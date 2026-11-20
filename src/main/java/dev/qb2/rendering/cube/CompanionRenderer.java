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
  private static final float[] SENSOR_OFFSETS = {-0.064f, 0.064f};
  private static final java.util.Set<String> BEAM_ACTIONS =
      java.util.Set.of("go_to", "inspect", "approach", "orbit");
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
    boolean napping = "Napping".equals(session.state);
    double bob = napping ? 0.0 : Math.sin(time * 2) * 0.025;
    poses.pushPose();
    poses.translate(
        position.x() - camera.x,
        position.y() - camera.y + bob,
        position.z() - camera.z);
    poses.mulPose(Axis.YP.rotationDegrees(frame.yaw()));
    poses.mulPose(Axis.ZP.rotationDegrees(frame.roll()));
    poses.mulPose(Axis.XP.rotationDegrees(frame.pitch()));
    var buffers = mc.renderBuffers().bufferSource();
    var vertices = buffers.getBuffer(type);
    box(
        poses, vertices, -0.125f, -0.125f, -0.125f, 0.125f, 0.125f, 0.125f, 0.035f, 0.045f, 0.060f,
        1);
    // Keep the sensor & edge geometry in the same full-bright material pass as the body.
    // Separate eyes/emissive passes are discarded by some Iris/Sodium pack pipelines.
    var light = vertices;
    float intensity = (float) (0.45 + settings.glow * frame.light() * 0.55);
    boolean thinking = "Thinking".equals(session.state);
    boolean speaking = "Speaking".equals(session.state);
    boolean errorVisual = session.apiBackoff || session.errorTintTicks > 0;
    // State-reactive glowing edges: blue = idle, dim indigo = napping, orange = thinking/request,
    // cyan-white = speaking, reddish = API error/backoff.
    float er, eg, eb;
    if (errorVisual) {
      er = intensity;
      eg = intensity * 0.22f;
      eb = intensity * 0.14f;
    } else if (thinking) {
      er = intensity;
      eg = intensity * 0.54f;
      eb = intensity * 0.10f;
    } else if (speaking) {
      er = intensity * 0.65f;
      eg = intensity * 0.96f;
      eb = intensity;
    } else if (napping) {
      er = intensity * 0.14f;
      eg = intensity * 0.34f;
      eb = intensity * 0.72f;
    } else {
      er = intensity * 0.22f;
      eg = intensity * 0.68f;
      eb = intensity;
    }
    renderEdges(poses, light, 0.1265f, 0.011f, er, eg, eb, 1.0f);

    float sensorScale = napping ? 0.42f : 1.0f;
    float sr = (errorVisual ? intensity : thinking ? intensity : intensity * 0.90f) * sensorScale;
    float sg = (errorVisual ? intensity * 0.35f : thinking ? intensity * 0.78f : intensity * 0.97f) * sensorScale;
    float sb = (errorVisual ? intensity * 0.22f : thinking ? intensity * 0.40f : intensity) * sensorScale;
    // Four equal sensor tiles form a technical array, without eyes, mouth or facial animation.
    for (float x : SENSOR_OFFSETS)
      for (float y : SENSOR_OFFSETS)
        box(
            poses,
            light,
            x - 0.022f,
            y - 0.022f,
            0.1251f,
            x + 0.022f,
            y + 0.022f,
            0.127f,
            sr,
            sg,
            sb,
            1);
    buffers.endBatch(type);
    poses.popPose();
    if (settings.showTargetBeam
        && session.activeTarget() != null
        && BEAM_ACTIONS.contains(session.action)) {
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

  private static void renderEdges(
      PoseStack p, VertexConsumer v, float h, float t, float r, float g, float b, float a) {
    // 4 edges along X
    box(p, v, -h, -h, -h, h, -h + t, -h + t, r, g, b, a);
    box(p, v, -h, h - t, -h, h, h, -h + t, r, g, b, a);
    box(p, v, -h, -h, h - t, h, -h + t, h, r, g, b, a);
    box(p, v, -h, h - t, h - t, h, h, h, r, g, b, a);
    // 4 edges along Y
    box(p, v, -h, -h, -h, -h + t, h, -h + t, r, g, b, a);
    box(p, v, h - t, -h, -h, h, h, -h + t, r, g, b, a);
    box(p, v, -h, -h, h - t, -h + t, h, h, r, g, b, a);
    box(p, v, h - t, -h, h - t, h, h, h, r, g, b, a);
    // 4 edges along Z
    box(p, v, -h, -h, -h, -h + t, -h + t, h, r, g, b, a);
    box(p, v, h - t, -h, -h, h, -h + t, h, r, g, b, a);
    box(p, v, -h, h - t, -h, -h + t, h, h, r, g, b, a);
    box(p, v, h - t, h - t, -h, h, h, h, r, g, b, a);
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
    PoseStack.Pose pose = p.last();
    quad(pose, v, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, r, g, b, a);
    quad(pose, v, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, r, g, b, a);
    quad(pose, v, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, r, g, b, a);
    quad(pose, v, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, r, g, b, a);
    quad(pose, v, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, r, g, b, a);
    quad(pose, v, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, r, g, b, a);
  }

  private static void quad(
      PoseStack.Pose pose,
      VertexConsumer v,
      float x0,
      float y0,
      float z0,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float x3,
      float y3,
      float z3,
      float nx,
      float ny,
      float nz,
      float r,
      float g,
      float b,
      float a) {
    vertex(pose, v, x0, y0, z0, 0, 1, nx, ny, nz, r, g, b, a);
    vertex(pose, v, x1, y1, z1, 1, 1, nx, ny, nz, r, g, b, a);
    vertex(pose, v, x2, y2, z2, 1, 0, nx, ny, nz, r, g, b, a);
    vertex(pose, v, x3, y3, z3, 0, 0, nx, ny, nz, r, g, b, a);
  }

  private static void vertex(
      PoseStack.Pose pose,
      VertexConsumer v,
      float x,
      float y,
      float z,
      float u,
      float tv,
      float nx,
      float ny,
      float nz,
      float r,
      float g,
      float b,
      float a) {
    v.addVertex(pose, x, y, z)
        .setColor(r, g, b, a)
        .setUv(u, tv)
        .setOverlay(OverlayTexture.NO_OVERLAY)
        .setLight(LightTexture.FULL_BRIGHT)
        .setNormal(pose, nx, ny, nz);
  }
}
