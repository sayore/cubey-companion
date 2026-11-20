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
  private final dev.qb2.rendering.animation.MorphState morph =
      new dev.qb2.rendering.animation.MorphState();
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
    var sit = session.situational;
    Point look =
        session.guideDestination != null
            ? session.guideDestination
            : sit.focusLookOverride != null
                ? sit.focusLookOverride
                : "Napping".equals(session.state) && session.perchLook != null
                    ? session.perchLook
                    : session.attention.target() == null
                            || session.bubbles.speaking(session.tick)
                            || sit.greetingTicks > 0
                            || sit.concernTicks > 0
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
    boolean napping = session.isSleeping();
    double sleepBreath = Math.sin(time * 0.72);
    double bob =
        napping
            ? -0.014
            : sit.socialBob
                + (sit.fishingBiteHop
                    ? Math.abs(Math.sin(time * 12.0)) * 0.16
                    : sit.dancing
                        ? Math.abs(Math.sin(time * 5.0)) * 0.08
                        : sit.eatingWithPlayer
                            ? Math.sin(time * 9.0) * 0.035
                            : Math.sin(time * (sit.sneaking ? 1.1 : 2.0))
                                * (sit.sneaking ? 0.012 : 0.025));

    // Situational micro-animations (strictly 0 extra motion when powered down / napping!)
    float extraYaw =
        napping
            ? 0f
            : sit.socialYaw
                + (sit.celebrateTicks > 0
                    ? (45 - sit.celebrateTicks + partial) * 8.0f
                    : sit.itemDropTicks > 30 ? (45 - sit.itemDropTicks) * 4.0f : 0f);
    float extraPitch =
        napping
            ? 14.0f
            : sit.socialPitch
                + (sit.greetingTicks > 0 ? (float) (Math.sin(time * 14.0) * 16.0) : 0f)
                + (sit.eatingWithPlayer ? (float) (Math.sin(time * 12.0) * 10.0) : 0f)
                + (sit.abyssLookTicks > 0 ? -38.0f : 0f);
    float extraRoll =
        napping
            ? 0f
            : sit.socialRoll
                + (sit.barrelRollTicks > 0
                    ? (28 - sit.barrelRollTicks + partial) * (360.0f / 28.0f)
                    : 0f)
                + sit.wingmanRoll
                + (sit.peeking || sit.petPlayTicks > 0 ? 13.0f : 0f)
                + (sit.greetingTicks > 0 ? (float) (Math.sin(time * 10.0) * 10.0) : 0f)
                + (sit.rainShiverTicks > 0 || sit.lightningStartleTicks > 30
                    ? (float) (Math.sin(time * 36.0) * 12.0)
                    : 0f)
                + (sit.dancing ? (float) (Math.sin(time * 4.5) * 12.0) : 0f);

    float finalYaw = frame.yaw() + extraYaw;
    float finalRoll = (napping ? 0f : frame.roll()) + extraRoll;
    float finalPitch = (napping ? 0f : frame.pitch()) + extraPitch;
    session.lastRenderedYaw = finalYaw;
    session.lastRenderedPitch = finalPitch;
    session.lastRenderedRoll = finalRoll;

    poses.pushPose();
    poses.translate(
        position.x() - camera.x,
        position.y() - camera.y + bob,
        position.z() - camera.z);
    poses.mulPose(Axis.YP.rotationDegrees(finalYaw));
    poses.mulPose(Axis.ZP.rotationDegrees(finalRoll));
    poses.mulPose(Axis.XP.rotationDegrees(finalPitch));
    if (napping) {
      float bx = (float) (1.0 + sleepBreath * 0.016);
      float by = (float) (1.0 - sleepBreath * 0.012);
      poses.scale(bx, by, bx);
    }
    var buffers = mc.renderBuffers().bufferSource();
    var vertices = buffers.getBuffer(type);
    var light = vertices;
    float baseIntensity = (float) (0.45 + settings.glow * frame.light() * 0.55);
    float intensity =
        sit.darkMode
            ? Math.min(1.0f, baseIntensity * 1.18f)
            : sit.sneaking ? baseIntensity * 0.65f : baseIntensity;
    boolean thinking = "Thinking".equals(session.state);
    boolean speaking = "Speaking".equals(session.state);
    boolean curious =
        "Curious".equals(session.state)
            || sit.peeking
            || sit.itemDropTicks > 0
            || sit.oreSparkleTicks > 0
            || sit.abyssLookTicks > 0;
    boolean happySocial =
        sit.celebrateTicks > 0
            || sit.greetingTicks > 0
            || sit.dancing
            || sit.petPlayTicks > 0
            || sit.social.isActive();
    boolean threatOrAiming =
        sit.threatTicks > 0 || sit.concernTicks > 0 || sit.aimingSpotter || sit.lightningStartleTicks > 0;
    boolean inspectingTarget =
        session.guideDestination != null
            || (session.activeTarget() != null && BEAM_ACTIONS.contains(session.action));
    boolean errorVisual = session.apiBackoff || session.errorTintTicks > 0;

    var ms =
        morph.update(
            dt,
            time,
            session.motion.speed(),
            napping,
            thinking,
            speaking,
            curious,
            sit.wingmanMode,
            happySocial,
            threatOrAiming,
            inspectingTarget);

    // State-reactive glowing edges
    float er, eg, eb;
    if (sit.lightningStartleTicks > 0) {
      er = intensity;
      eg = intensity;
      eb = intensity;
    } else if (sit.threatTicks > 0 || sit.concernTicks > 0) {
      er = intensity;
      eg = intensity * 0.42f;
      eb = intensity * 0.08f;
    } else if (errorVisual) {
      er = intensity;
      eg = intensity * 0.22f;
      eb = intensity * 0.14f;
    } else if (sit.dancing) {
      er = intensity * (float) (0.55 + 0.45 * Math.sin(time * 3.0));
      eg = intensity * (float) (0.55 + 0.45 * Math.sin(time * 3.0 + 2.1));
      eb = intensity * (float) (0.55 + 0.45 * Math.sin(time * 3.0 + 4.2));
    } else if (sit.oreSparkleTicks > 0) {
      er = intensity * 0.30f;
      eg = intensity;
      eb = intensity * 0.78f;
    } else if (sit.portalResonance) {
      er = intensity * 0.78f;
      eg = intensity * 0.22f;
      eb = intensity;
    } else if (sit.celebrateTicks > 0) {
      er = intensity * 0.95f;
      eg = intensity * 0.92f;
      eb = intensity * 0.35f;
    } else if (thinking) {
      er = intensity;
      eg = intensity * 0.54f;
      eb = intensity * 0.10f;
    } else if (speaking) {
      er = intensity * 0.65f;
      eg = intensity * 0.96f;
      eb = intensity;
    } else if (napping) {
      er = sit.warmGlow ? intensity * 0.42f : intensity * 0.14f;
      eg = sit.warmGlow ? intensity * 0.46f : intensity * 0.34f;
      eb = sit.warmGlow ? intensity * 0.68f : intensity * 0.72f;
    } else if (sit.sunsetGlow) {
      er = intensity * 0.96f;
      eg = intensity * 0.58f;
      eb = intensity * 0.72f;
    } else if (sit.warmGlow) {
      er = intensity * 0.55f;
      eg = intensity * 0.78f;
      eb = intensity * 0.92f;
    } else {
      er = intensity * 0.22f;
      eg = intensity * 0.68f;
      eb = intensity;
    }

    poses.scale(ms.stretchXZ(), ms.squashY(), ms.stretchXZ() + ms.aeroDelta() * 0.14f);

    float sensorScale = napping ? (float) (0.20 + (sleepBreath + 1.0) * 0.06) : 1.0f;
    float sr = (errorVisual ? intensity : thinking ? intensity : intensity * 0.90f) * sensorScale;
    float sg =
        (errorVisual ? intensity * 0.35f : thinking ? intensity * 0.78f : intensity * 0.97f)
            * sensorScale;
    float sb =
        (errorVisual ? intensity * 0.22f : thinking ? intensity * 0.40f : intensity) * sensorScale;

    renderNeoFluidBody(
        poses,
        light,
        time,
        sleepBreath,
        napping,
        thinking,
        ms,
        sit.activeHat,
        intensity,
        er,
        eg,
        eb,
        sr,
        sg,
        sb);
    buffers.endBatch(type);
    poses.popPose();
    if (settings.showTargetBeam) {
      if (session.guideDestination != null) {
        renderTargetBeam(buffers, camera, position, session.guideDestination, intensity);
      } else if (session.activeTarget() != null && BEAM_ACTIONS.contains(session.action)) {
        renderTargetBeam(buffers, camera, position, session.activeTarget().position(), intensity);
      }
    }
    if (sit.socialLinkTarget != null) {
      renderSocialResonanceLink(buffers, camera, position, sit.socialLinkTarget, time, intensity);
    }
    bubbles.render(event, session, settings, position);
    if (settings.multiplayerSync) {
      renderPeerCubeys(event, mc, buffers, camera, session, settings, time, partial);
    }
  }

  private void renderPeerCubeys(
      RenderLevelStageEvent event,
      Minecraft mc,
      MultiBufferSource.BufferSource buffers,
      net.minecraft.world.phys.Vec3 camera,
      CompanionSession session,
      Settings settings,
      double time,
      float partial) {
    var peers = session.peers.activePeers();
    if (peers.isEmpty()) return;
    double sleepBreath = Math.sin(time * 0.72);
    float intensity = (float) (0.45 + settings.glow * 0.82 * 0.55);
    for (var peer : peers) {
      Point pPos = peer.interpolatePos(partial);
      boolean pNap = "Napping".equals(peer.state);
      boolean pThink = "Thinking".equals(peer.state);
      boolean pSpeak = "Speaking".equals(peer.state);
      double bob = pNap ? -0.014 : Math.sin(time * 2.0 + peer.ownerId.hashCode()) * 0.025;

      PoseStack poses = new PoseStack();
      poses.pushPose();
      poses.translate(pPos.x() - camera.x, pPos.y() - camera.y + bob, pPos.z() - camera.z);
      poses.mulPose(Axis.YP.rotationDegrees(peer.interpolateYaw(partial)));
      poses.mulPose(Axis.ZP.rotationDegrees(pNap ? 0f : peer.roll));
      poses.mulPose(Axis.XP.rotationDegrees(pNap ? 14f : peer.pitch));
      if (pNap) {
        float bx = (float) (1.0 + sleepBreath * 0.016);
        float by = (float) (1.0 - sleepBreath * 0.012);
        poses.scale(bx, by, bx);
      }
      var v = buffers.getBuffer(type);
      float er = pThink ? intensity : pSpeak ? intensity * 0.65f : pNap ? intensity * 0.14f : intensity * 0.22f;
      float eg = pThink ? intensity * 0.54f : pSpeak ? intensity * 0.96f : pNap ? intensity * 0.34f : intensity * 0.68f;
      float eb = pThink ? intensity * 0.10f : pSpeak ? intensity : pNap ? intensity * 0.72f : intensity;
      boolean pWingman = "Wingman".equals(peer.state);
      boolean pCurious = "Curious".equals(peer.state) || "Guiding".equals(peer.state);
      var peerMs =
          new dev.qb2.rendering.animation.MorphState.Snapshot(
              pThink ? 1.0f : pCurious ? 0.55f : 0f,
              pSpeak ? 0.95f : pCurious ? 0.65f : 0.15f,
              pWingman ? 1.0f : 0f,
              pNap ? 1.0f : 0f,
              pWingman ? -0.028f : 0f,
              0f,
              pNap ? 0.84f : 1.0f,
              pNap ? 1.10f : 1.0f,
              pNap
                  ? dev.qb2.rendering.animation.MorphState.EyeMood.SLEEP_SLITS
                  : pThink
                      ? dev.qb2.rendering.animation.MorphState.EyeMood.ORBIT_SPINNER
                      : pSpeak
                          ? dev.qb2.rendering.animation.MorphState.EyeMood.VOICE_WAVE
                          : pCurious
                              ? dev.qb2.rendering.animation.MorphState.EyeMood.CURIOUS_ASYM
                              : dev.qb2.rendering.animation.MorphState.EyeMood.NEUTRAL_BLINK);
      poses.scale(peerMs.stretchXZ(), peerMs.squashY(), peerMs.stretchXZ() + peerMs.aeroDelta() * 0.14f);
      float sScale = pNap ? 0.26f : 1.0f;
      renderNeoFluidBody(
          poses,
          v,
          time,
          sleepBreath,
          pNap,
          pThink,
          peerMs,
          peer.hat,
          intensity,
          er,
          eg,
          eb,
          intensity * 0.90f * sScale,
          intensity * 0.97f * sScale,
          intensity * sScale);
      buffers.endBatch(type);
      poses.popPose();

      // Render peer nametag / shared speech above the remote Cubey
      String label =
          (peer.speechUntilTick > session.tick && !peer.speech.isBlank())
              ? peer.ownerName + "'s QB2: " + peer.speech
              : peer.ownerName + "'s QB2";
      double dist = pPos.distance(new Point(camera.x, camera.y, camera.z));
      if (dist >= 0.5 && dist <= 32.0) {
        PoseStack tagPose = new PoseStack();
        tagPose.translate(pPos.x() - camera.x, pPos.y() - camera.y + 0.30, pPos.z() - camera.z);
        tagPose.mulPose(event.getCamera().rotation());
        tagPose.scale(0.014f, -0.014f, 0.014f);
        String clipped = mc.font.plainSubstrByWidth(label, 150);
        float tx = -mc.font.width(clipped) / 2f;
        mc.font.drawInBatch(
            clipped,
            tx,
            0,
            0xE0B8E8FF,
            false,
            tagPose.last().pose(),
            buffers,
            net.minecraft.client.gui.Font.DisplayMode.NORMAL,
            0x70102030,
            LightTexture.FULL_BRIGHT);
        buffers.endBatch();
      }
    }
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

  private void renderSocialResonanceLink(
      MultiBufferSource.BufferSource buffers,
      net.minecraft.world.phys.Vec3 camera,
      Point from,
      Point to,
      double time,
      float intensity) {
    double dist = from.distance(to);
    if (dist < 0.15 || dist > 7.0) return;
    PoseStack arcPose = new PoseStack();
    arcPose.pushPose();
    var lineBuf = buffers.getBuffer(RenderType.lines());
    int segments = 8;
    double prevX = from.x() - camera.x;
    double prevY = from.y() - camera.y;
    double prevZ = from.z() - camera.z;
    for (int i = 1; i <= segments; i++) {
      double t = i / (double) segments;
      double wave = Math.sin(t * Math.PI) * (0.14 + 0.04 * Math.sin(time * 8.0));
      double curX = (from.x() + (to.x() - from.x()) * t) - camera.x;
      double curY = (from.y() + (to.y() - from.y()) * t + wave) - camera.y;
      double curZ = (from.z() + (to.z() - from.z()) * t) - camera.z;
      float r = (float) (0.45 + 0.45 * Math.sin(time * 5.0 + t * 3.0)) * intensity;
      float g = 0.92f * intensity;
      float b = 1.0f * intensity;
      lineBuf
          .addVertex(arcPose.last(), (float) prevX, (float) prevY, (float) prevZ)
          .setColor(r, g, b, 0.72f)
          .setNormal(arcPose.last(), 0, 1, 0);
      lineBuf
          .addVertex(arcPose.last(), (float) curX, (float) curY, (float) curZ)
          .setColor(r, g, b, 0.72f)
          .setNormal(arcPose.last(), 0, 1, 0);
      prevX = curX;
      prevY = curY;
      prevZ = curZ;
    }
    buffers.endBatch(RenderType.lines());
    arcPose.popPose();
  }

  private static final float[] CORNER_SIGNS = {-1f, 1f};

  /**
   * Renders Cubey's Neo-Fluid / Sci-Fi morphing body:
   * 1) Dual counter-rotating gyro-plasma core inside the cavity.
   * 2) 6 magnetically floating split-shell plates that expand into an open hyper-tesseract or
   *    bulge with stepped sub-domes into an organic ferrofluid blob.
   * 3) 8 detachable corner shards that orbit, breathe, or retract into soft energy droplets.
   * 4) Aerodynamic delta winglets + 3-node trailing ion afterburner in fast flight.
   * 5) 7-mode expressive front-plate Neo-Eyes.
   */
  private static void renderNeoFluidBody(
      PoseStack poses,
      VertexConsumer v,
      double time,
      double sleepBreath,
      boolean napping,
      boolean thinking,
      dev.qb2.rendering.animation.MorphState.Snapshot ms,
      String hat,
      float intensity,
      float er,
      float eg,
      float eb,
      float sr,
      float sg,
      float sb) {
    float open = ms.tesseractOpen();
    float blob = ms.blobMorph();
    float aero = ms.aeroDelta();
    float sleep = ms.mochiSleep();

    // 1. Retractable inner dark chassis + Dual counter-rotating Gyro-Plasma Core
    float coreHalf = 0.098f - open * 0.026f - blob * 0.022f + sleep * 0.014f;
    box(
        poses,
        v,
        -coreHalf,
        -coreHalf,
        -coreHalf,
        coreHalf,
        coreHalf,
        coreHalf,
        0.028f + er * 0.08f,
        0.038f + eg * 0.08f,
        0.054f + eb * 0.08f,
        1f);

    if (!napping && (open > 0.04f || blob > 0.12f)) {
      renderInnerGyroCore(poses, v, time, open, blob, er, eg, eb);
    }

    // 2. Six magnetically floating split-shell plates + organic liquid-harmonic blob sub-domes
    float frontPlateZ =
        renderMagneticSplitShell(poses, v, time, open, blob, aero, sleep, ms.springLagZ(), ms.springLagY(), er, eg, eb);

    // 3. Glowing energy frame / edge conduits (breathe outward with tesseract/blob expansion)
    float edgeSpan = 0.120f + open * 0.022f - blob * 0.010f - sleep * 0.005f;
    float edgeThick = 0.0085f * (1.0f - blob * 0.35f);
    renderEdges(poses, v, edgeSpan, edgeThick, er, eg, eb, 1.0f);

    // 4. Eight detachable orbiting corner shards
    renderCornerPods(poses, v, time, sleepBreath, napping, thinking, ms, er, eg, eb);

    // 5. Articulated flank winglets + 3-node aerodynamic ion trail
    renderSideWinglets(poses, v, time, napping, ms, er, eg, eb);
    if (aero > 0.12f && !napping) {
      renderAeroIonTrail(poses, v, time, aero, ms.springLagZ(), er, eg, eb);
    }

    // 6. Floating 3D hat (tracks top shell plate height!)
    float topHatOffset = open * 0.026f + blob * 0.014f - sleep * 0.008f;
    poses.pushPose();
    poses.translate(0, topHatOffset, ms.springLagZ() * 0.35f);
    renderHat(poses, v, hat, time, intensity);
    poses.popPose();

    // 7. Expressive 7-mode Neo-Eyes mounted on the moving front shell plate
    renderNeoEyes(poses, v, time, ms.eyeMood(), frontPlateZ, sr, sg, sb);
  }

  private static void renderInnerGyroCore(
      PoseStack p,
      VertexConsumer v,
      double time,
      float open,
      float blob,
      float er,
      float eg,
      float eb) {
    float spinSpeed = (float) (time * (95.0 + open * 140.0));
    float ringSize = 0.056f + open * 0.016f + blob * 0.010f;
    // Outer gyro ring rotating around Y + X
    p.pushPose();
    p.mulPose(Axis.YP.rotationDegrees(spinSpeed));
    p.mulPose(Axis.XP.rotationDegrees(spinSpeed * 0.7f));
    renderEdges(p, v, ringSize, 0.008f, er, eg, eb, 1.0f);
    p.popPose();

    // Inner counter-rotating plasma core cube
    float pulse = (float) (0.032 + Math.sin(time * 7.5) * 0.007 * (1.0 + blob));
    p.pushPose();
    p.mulPose(Axis.ZP.rotationDegrees(-spinSpeed * 1.1f));
    p.mulPose(Axis.YP.rotationDegrees(-spinSpeed * 0.8f));
    box(
        p,
        v,
        -pulse,
        -pulse,
        -pulse,
        pulse,
        pulse,
        pulse,
        Math.min(1f, er * 1.15f + 0.15f),
        Math.min(1f, eg * 1.15f + 0.15f),
        Math.min(1f, eb * 1.15f + 0.15f),
        1f);
    p.popPose();
  }

  /**
   * Renders the 6 floating magnetic shell plates (+Z, -Z, -X, +X, +Y, -Y) and their stepped
   * organic blob sub-domes. Returns the outer Z coordinate of the front plate for eye mounting.
   */
  private static float renderMagneticSplitShell(
      PoseStack p,
      VertexConsumer v,
      double time,
      float open,
      float blob,
      float aero,
      float sleep,
      float lagZ,
      float lagY,
      float er,
      float eg,
      float eb) {
    float plateSpan = 0.088f - open * 0.008f - blob * 0.012f + sleep * 0.024f;
    float baseDist = 0.100f + open * 0.028f - sleep * 0.006f;
    float thick = 0.018f;

    float pr = 0.036f + er * 0.10f;
    float pg = 0.046f + eg * 0.10f;
    float pb = 0.064f + eb * 0.10f;

    // Harmonic liquid waves for each face in Blob mode
    float wFront = (float) (Math.sin(time * 6.4) * 0.014 * blob);
    float wBack = (float) (Math.sin(time * 6.4 + 2.1) * 0.014 * blob);
    float wLeft = (float) (Math.sin(time * 6.4 + 1.05) * 0.016 * blob);
    float wRight = (float) (Math.sin(time * 6.4 + 3.15) * 0.016 * blob);
    float wTop = (float) (Math.sin(time * 6.4 + 4.2) * 0.015 * blob);
    float wBot = (float) (Math.sin(time * 6.4 + 5.25) * 0.012 * blob);

    // 1. Front Plate (+Z) - pushes forward in aero mode
    float frontZ0 = baseDist + wFront + aero * 0.016f;
    float frontZ1 = frontZ0 + thick;
    box(p, v, -plateSpan, -plateSpan, frontZ0, plateSpan, plateSpan, frontZ1, pr, pg, pb, 1f);

    // 2. Back Plate (-Z) - trails with spring lag
    float backZ1 = -(baseDist + wBack) + lagZ * 0.7f;
    float backZ0 = backZ1 - thick;
    box(p, v, -plateSpan, -plateSpan, backZ0, plateSpan, plateSpan, backZ1, pr, pg, pb, 1f);

    // 3. Left & Right Plates (-X, +X) - flare out & sweep back in aero/tesseract mode
    float sideX = baseDist + aero * 0.012f;
    float sideLagZ = lagZ * 0.55f - aero * 0.015f;
    box(
        p,
        v,
        -(sideX + wLeft + thick),
        -plateSpan + lagY * 0.4f,
        -plateSpan + sideLagZ,
        -(sideX + wLeft),
        plateSpan + lagY * 0.4f,
        plateSpan + sideLagZ,
        pr,
        pg,
        pb,
        1f);
    box(
        p,
        v,
        sideX + wRight,
        -plateSpan + lagY * 0.4f,
        -plateSpan + sideLagZ,
        sideX + wRight + thick,
        plateSpan + lagY * 0.4f,
        plateSpan + sideLagZ,
        pr,
        pg,
        pb,
        1f);

    // 4. Top & Bottom Plates (+Y, -Y)
    float topY0 = baseDist + wTop + lagY * 0.5f;
    box(
        p,
        v,
        -plateSpan,
        topY0,
        -plateSpan + lagZ * 0.35f,
        plateSpan,
        topY0 + thick,
        plateSpan + lagZ * 0.35f,
        pr,
        pg,
        pb,
        1f);
    float botY1 = -(baseDist + wBot);
    box(p, v, -plateSpan, botY1 - thick, -plateSpan, plateSpan, botY1, plateSpan, pr, pg, pb, 1f);

    // 5. Organic Fluid-Blob Sub-Domes (rounds out the 6 faces into a soft pulsing blob!)
    if (blob > 0.18f && sleep < 0.5f) {
      float domeSpan = plateSpan * 0.64f;
      float domeH = 0.013f * blob;
      float dr = 0.048f + er * 0.26f;
      float dg = 0.062f + eg * 0.26f;
      float db = 0.088f + eb * 0.26f;
      // Lateral & vertical soft domes
      box(
          p,
          v,
          -(sideX + wLeft + thick + domeH),
          -domeSpan,
          -domeSpan + sideLagZ,
          -(sideX + wLeft + thick),
          domeSpan,
          domeSpan + sideLagZ,
          dr,
          dg,
          db,
          1f);
      box(
          p,
          v,
          sideX + wRight + thick,
          -domeSpan,
          -domeSpan + sideLagZ,
          sideX + wRight + thick + domeH,
          domeSpan,
          domeSpan + sideLagZ,
          dr,
          dg,
          db,
          1f);
      box(
          p,
          v,
          -domeSpan,
          topY0 + thick,
          -domeSpan,
          domeSpan,
          topY0 + thick + domeH,
          domeSpan,
          dr,
          dg,
          db,
          1f);
      box(
          p,
          v,
          -domeSpan,
          -domeSpan,
          backZ0 - domeH,
          domeSpan,
          domeSpan,
          backZ0,
          dr,
          dg,
          db,
          1f);
    }

    return frontZ1;
  }

  /**
   * 7-mode expressive front-plate Neo-Eyes:
   * SLEEP_SLITS, HAPPY_ARCS (^ ^), CURIOUS_ASYM (o O), FOCUS_VISOR (\ /),
   * ORBIT_SPINNER (revolving neural nodes), VOICE_WAVE (audio equalizer), NEUTRAL_BLINK.
   */
  private static void renderNeoEyes(
      PoseStack p,
      VertexConsumer v,
      double time,
      dev.qb2.rendering.animation.MorphState.EyeMood mood,
      float frontZ,
      float sr,
      float sg,
      float sb) {
    float z0 = frontZ + 0.001f;
    float z1 = frontZ + 0.007f;

    switch (mood) {
      case SLEEP_SLITS -> {
        float h = 0.0035f;
        for (float x : SENSOR_OFFSETS)
          for (float y : SENSOR_OFFSETS)
            box(p, v, x - 0.022f, y - h, z0, x + 0.022f, y + h, z1, sr, sg, sb, 1f);
      }
      case HAPPY_ARCS -> {
        // Two cheerful upward chevron arcs (^ ^) on left and right eye columns
        for (float cx : SENSOR_OFFSETS) {
          // Peak of the happy arc
          box(p, v, cx - 0.016f, 0.028f, z0, cx + 0.016f, 0.044f, z1, sr, sg, sb, 1f);
          // Left & right downward tips of each happy arc
          box(p, v, cx - 0.028f, 0.010f, z0, cx - 0.012f, 0.030f, z1, sr, sg, sb, 1f);
          box(p, v, cx + 0.012f, 0.010f, z0, cx + 0.028f, 0.030f, z1, sr, sg, sb, 1f);
          // Warm lower cheek glow dots
          box(
              p,
              v,
              cx - 0.018f,
              -0.056f,
              z0,
              cx + 0.018f,
              -0.042f,
              z1,
              Math.min(1f, sr * 1.1f),
              sg * 0.78f,
              sb * 0.85f,
              1f);
        }
      }
      case CURIOUS_ASYM -> {
        // Left column: focused narrow scan sensors; Right column: wide zoomed lens (o_O)
        for (float y : SENSOR_OFFSETS) {
          // Left (-0.064): narrow horizontal scan slit
          box(p, v, -0.086f, y - 0.012f, z0, -0.042f, y + 0.012f, z1, sr, sg, sb, 1f);
          // Right (+0.064): expanded high-aperture sensor + inner bright pupil
          float zoom = y > 0 ? 0.030f : 0.024f;
          box(p, v, 0.064f - zoom, y - zoom, z0, 0.064f + zoom, y + zoom, z1, sr, sg, sb, 1f);
        }
      }
      case FOCUS_VISOR -> {
        // Sharp tactical V-slanted visor bars (\ /)
        box(p, v, -0.090f, 0.036f, z0, -0.056f, 0.056f, z1, sr, sg, sb, 1f);
        box(p, v, -0.062f, 0.020f, z0, -0.026f, 0.040f, z1, sr, sg, sb, 1f);
        box(p, v, 0.026f, 0.020f, z0, 0.062f, 0.040f, z1, sr, sg, sb, 1f);
        box(p, v, 0.056f, 0.036f, z0, 0.090f, 0.056f, z1, sr, sg, sb, 1f);
        // Lower lock-on reticles
        for (float x : SENSOR_OFFSETS)
          box(p, v, x - 0.020f, -0.066f, z0, x + 0.020f, -0.048f, z1, sr, sg, sb, 1f);
      }
      case ORBIT_SPINNER -> {
        // 4 sensor nodes revolving around the front plate center like a neural loading ring
        for (int i = 0; i < 4; i++) {
          double ang = time * 5.8 + i * (Math.PI / 2.0);
          float rx = (float) (Math.cos(ang) * 0.064);
          float ry = (float) (Math.sin(ang) * 0.064);
          float hs = (i % 2 == 0) ? 0.022f : 0.017f;
          box(p, v, rx - hs, ry - hs, z0, rx + hs, ry + hs, z1, sr, sg, sb, 1f);
        }
      }
      case VOICE_WAVE -> {
        // Upper eyes stay attentive while lower sensors pulse as a 4-bar audio waveform equalizer
        for (float x : SENSOR_OFFSETS) {
          box(p, v, x - 0.022f, 0.042f, z0, x + 0.022f, 0.084f, z1, sr, sg, sb, 1f);
        }
        float[] barX = {-0.072f, -0.024f, 0.024f, 0.072f};
        for (int i = 0; i < 4; i++) {
          float amp = (float) (0.012 + Math.abs(Math.sin(time * 13.5 + i * 1.1)) * 0.032);
          box(
              p,
              v,
              barX[i] - 0.013f,
              -0.058f - amp,
              z0,
              barX[i] + 0.013f,
              -0.058f + amp,
              z1,
              sr,
              sg,
              sb,
              1f);
        }
      }
      default -> {
        // NEUTRAL_BLINK: natural 120ms blink every ~5.5 seconds
        double blinkCycle = time % 5.5;
        float eyeHalfH = (blinkCycle > 5.36) ? 0.004f : 0.022f;
        for (float x : SENSOR_OFFSETS)
          for (float y : SENSOR_OFFSETS)
            box(p, v, x - 0.022f, y - eyeHalfH, z0, x + 0.022f, y + eyeHalfH, z1, sr, sg, sb, 1f);
      }
    }
  }

  /**
   * 8 detachable corner shards that pull inward when sleeping, orbit & float outward when thinking
   * or morphing into a blob, and trail behind with spring inertia.
   */
  private static void renderCornerPods(
      PoseStack p,
      VertexConsumer v,
      double time,
      double sleepBreath,
      boolean napping,
      boolean thinking,
      dev.qb2.rendering.animation.MorphState.Snapshot ms,
      float er,
      float eg,
      float eb) {
    float open = ms.tesseractOpen();
    float blob = ms.blobMorph();
    float aero = ms.aeroDelta();
    int idx = 0;
    for (float sx : CORNER_SIGNS) {
      for (float sy : CORNER_SIGNS) {
        for (float sz : CORNER_SIGNS) {
          double phase = idx++ * 0.8;
          float pullOut;
          if (napping) {
            pullOut = (float) (0.001 + (sleepBreath + 1.0) * 0.002);
          } else {
            float harmonic = (float) ((Math.sin(time * (thinking ? 5.4 : 2.2) + phase) + 1.0) * 0.5);
            pullOut = 0.005f + open * (0.022f + harmonic * 0.014f) + blob * (0.008f + harmonic * 0.012f);
          }

          // Orbital swirl offset when tesseract or blob mode is active
          float swirlX =
              napping ? 0f : (float) (Math.cos(time * 3.8 + phase) * 0.014 * (open + blob * 0.5));
          float swirlY =
              napping ? 0f : (float) (Math.sin(time * 3.8 + phase) * 0.014 * (open + blob * 0.5));
          float sweepZ = (sz < 0) ? (ms.springLagZ() * 0.9f - aero * 0.022f) : (ms.springLagZ() * 0.3f);

          float cx = sx * (0.104f + pullOut) + swirlX;
          float cy = sy * (0.104f + pullOut) + swirlY + (sz < 0 ? ms.springLagY() * 0.6f : 0f);
          float cz = sz * (0.104f + pullOut) + sweepZ;

          // In high Blob mode, shrink the boxy metallic corner shell so the energy droplet dominates
          float hs = 0.024f * (1.0f - blob * 0.42f);
          box(
              p,
              v,
              cx - hs,
              cy - hs,
              cz - hs,
              cx + hs,
              cy + hs,
              cz + hs,
              0.055f + er * 0.22f,
              0.070f + eg * 0.22f,
              0.095f + eb * 0.22f,
              1f);
          // Glowing inner energy core droplet
          float cs = 0.014f + blob * 0.005f + open * 0.003f;
          float icx = sx * (0.092f + pullOut * 0.65f) + swirlX * 0.8f;
          float icy = sy * (0.092f + pullOut * 0.65f) + swirlY * 0.8f;
          float icz = sz * (0.092f + pullOut * 0.65f) + sweepZ * 0.8f;
          box(
              p,
              v,
              icx - cs,
              icy - cs,
              icz - cs,
              icx + cs,
              icy + cs,
              icz + cs,
              er * 0.95f,
              eg * 0.95f,
              eb * 0.95f,
              1f);
        }
      }
    }
  }

  /**
   * Two articulated side sensor winglets/fins that flare out into swept-back delta wings during
   * fast flight or wingman mode.
   */
  private static void renderSideWinglets(
      PoseStack p,
      VertexConsumer v,
      double time,
      boolean folded,
      dev.qb2.rendering.animation.MorphState.Snapshot ms,
      float er,
      float eg,
      float eb) {
    float aero = ms.aeroDelta();
    float open = ms.tesseractOpen();
    float span =
        folded
            ? 0.004f
            : (float) (0.016 + open * 0.014 + aero * 0.042 + Math.sin(time * 2.4) * 0.006);
    float drop = folded ? -0.012f : 0.004f - aero * 0.008f;
    float sweepBack = ms.springLagZ() * 0.6f - aero * 0.028f;
    float innerX = 0.118f + open * 0.022f;
    // Left & right flank winglets
    box(
        p,
        v,
        -innerX - span,
        -0.028f + drop,
        -0.048f + sweepBack,
        -innerX,
        0.028f + drop,
        0.042f + sweepBack * 0.4f,
        er * 0.82f,
        eg * 0.82f,
        eb * 0.82f,
        1f);
    box(
        p,
        v,
        innerX,
        -0.028f + drop,
        -0.048f + sweepBack,
        innerX + span,
        0.028f + drop,
        0.042f + sweepBack * 0.4f,
        er * 0.82f,
        eg * 0.82f,
        eb * 0.82f,
        1f);
  }

  /**
   * 3-node detached Ion Afterburner Trail that streams behind Cubey when flying rapidly or in
   * Wingman formation.
   */
  private static void renderAeroIonTrail(
      PoseStack p,
      VertexConsumer v,
      double time,
      float aero,
      float lagZ,
      float er,
      float eg,
      float eb) {
    for (int i = 1; i <= 3; i++) {
      float distBack = -0.135f - i * (0.048f + aero * 0.026f) + lagZ * i * 0.5f;
      float waveY = (float) (Math.sin(time * 14.0 - i * 1.3) * 0.012 * aero);
      float waveX = (float) (Math.cos(time * 11.0 - i * 1.1) * 0.010 * aero);
      float hs = (0.024f - i * 0.005f) * aero;
      if (hs <= 0.002f) continue;
      float fade = (1.0f - i * 0.22f);
      box(
          p,
          v,
          waveX - hs,
          waveY - hs,
          distBack - hs,
          waveX + hs,
          waveY + hs,
          distBack + hs,
          er * fade,
          eg * fade,
          eb * fade,
          1f);
    }
  }

  private static void renderHat(
      PoseStack p, VertexConsumer v, String hat, double time, float intensity) {
    if (hat == null || hat.equals("none")) return;
    float y = 0.126f;
    switch (hat) {
      case "crown" -> {
        // Golden crown rim + 4 corner spikes
        box(p, v, -0.075f, y, -0.075f, 0.075f, y + 0.025f, 0.075f, 0.95f, 0.78f, 0.18f, 1f);
        for (float cx : SENSOR_OFFSETS)
          for (float cz : SENSOR_OFFSETS)
            box(
                p,
                v,
                cx - 0.012f,
                y + 0.025f,
                cz - 0.012f,
                cx + 0.012f,
                y + 0.055f,
                cz + 0.012f,
                1.0f,
                0.88f,
                0.25f,
                1f);
      }
      case "miner" -> {
        // Yellow mining hard-hat + bright front headlamp
        box(p, v, -0.095f, y, -0.095f, 0.095f, y + 0.016f, 0.105f, 0.92f, 0.72f, 0.12f, 1f);
        box(p, v, -0.078f, y + 0.016f, -0.078f, 0.078f, y + 0.052f, 0.078f, 0.96f, 0.78f, 0.15f, 1f);
        box(
            p,
            v,
            -0.024f,
            y + 0.022f,
            0.079f,
            0.024f,
            y + 0.048f,
            0.092f,
            intensity,
            intensity,
            intensity * 0.9f,
            1f);
      }
      case "rain" -> {
        // Wide yellow rain hat brim + dome
        box(p, v, -0.145f, y, -0.145f, 0.145f, y + 0.012f, 0.145f, 0.95f, 0.82f, 0.16f, 1f);
        box(p, v, -0.075f, y + 0.012f, -0.075f, 0.075f, y + 0.048f, 0.075f, 0.90f, 0.76f, 0.14f, 1f);
      }
      case "sleep" -> {
        // Stepped blue nightcap with white pom-pom
        box(p, v, -0.080f, y, -0.080f, 0.080f, y + 0.022f, 0.080f, 0.22f, 0.42f, 0.85f, 1f);
        box(p, v, -0.055f, y + 0.022f, -0.065f, 0.055f, y + 0.048f, 0.045f, 0.26f, 0.48f, 0.92f, 1f);
        box(p, v, -0.022f, y + 0.048f, -0.078f, 0.022f, y + 0.075f, -0.034f, 0.95f, 0.97f, 1.0f, 1f);
      }
      case "propeller" -> {
        // Beanie base + spinning propeller blades
        box(p, v, -0.070f, y, -0.070f, 0.070f, y + 0.025f, 0.070f, 0.20f, 0.65f, 0.95f, 1f);
        box(p, v, -0.010f, y + 0.025f, -0.010f, 0.010f, y + 0.050f, 0.010f, 0.85f, 0.85f, 0.90f, 1f);
        boolean flip = ((int) (time * 18)) % 2 == 0;
        if (flip) {
          box(p, v, -0.085f, y + 0.050f, -0.012f, 0.085f, y + 0.058f, 0.012f, 1.0f, 0.45f, 0.15f, 1f);
        } else {
          box(p, v, -0.012f, y + 0.050f, -0.085f, 0.012f, y + 0.058f, 0.085f, 1.0f, 0.45f, 0.15f, 1f);
        }
      }
      case "chef" -> {
        // Little white chef toque
        box(p, v, -0.070f, y, -0.070f, 0.070f, y + 0.025f, 0.070f, 0.92f, 0.94f, 0.98f, 1f);
        box(p, v, -0.082f, y + 0.025f, -0.082f, 0.082f, y + 0.062f, 0.082f, 0.98f, 0.99f, 1.0f, 1f);
      }
      default -> {
        // Sci-fi sensor antenna mast + glowing tip
        box(p, v, -0.008f, y, -0.008f, 0.008f, y + 0.052f, 0.008f, 0.25f, 0.35f, 0.48f, 1f);
        box(
            p,
            v,
            -0.016f,
            y + 0.052f,
            -0.016f,
            0.016f,
            y + 0.076f,
            0.016f,
            0.40f * intensity,
            0.92f * intensity,
            intensity,
            1f);
      }
    }
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
