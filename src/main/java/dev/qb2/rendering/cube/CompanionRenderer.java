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
    // Main dark chassis
    box(
        poses, vertices, -0.118f, -0.118f, -0.118f, 0.118f, 0.118f, 0.118f, 0.032f, 0.042f, 0.058f,
        1);
    // Keep the sensor & edge geometry in the same full-bright material pass as the body.
    // Separate eyes/emissive passes are discarded by some Iris/Sodium pack pipelines.
    var light = vertices;
    float baseIntensity = (float) (0.45 + settings.glow * frame.light() * 0.55);
    float intensity =
        sit.darkMode ? Math.min(1.0f, baseIntensity * 1.18f) : sit.sneaking ? baseIntensity * 0.65f : baseIntensity;
    boolean thinking = "Thinking".equals(session.state);
    boolean speaking = "Speaking".equals(session.state);
    boolean errorVisual = session.apiBackoff || session.errorTintTicks > 0;
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
    renderEdges(poses, light, 0.121f, 0.009f, er, eg, eb, 1.0f);
    renderCornerPods(poses, light, time, sleepBreath, napping, thinking, sit.wingmanMode, er, eg, eb);
    renderSideWinglets(poses, light, time, napping || sit.sneaking, er, eg, eb);
    renderHat(poses, light, sit.activeHat, time, intensity);

    float sensorScale =
        napping ? (float) (0.20 + (sleepBreath + 1.0) * 0.06) : 1.0f;
    float sr = (errorVisual ? intensity : thinking ? intensity : intensity * 0.90f) * sensorScale;
    float sg = (errorVisual ? intensity * 0.35f : thinking ? intensity * 0.78f : intensity * 0.97f) * sensorScale;
    float sb = (errorVisual ? intensity * 0.22f : thinking ? intensity * 0.40f : intensity) * sensorScale;
    // Four equal sensor tiles: close into thin horizontal sleeping slits when powered down!
    float eyeHalfH = napping ? 0.0035f : 0.022f;
    for (float x : SENSOR_OFFSETS)
      for (float y : SENSOR_OFFSETS)
        box(
            poses,
            light,
            x - 0.022f,
            y - eyeHalfH,
            0.119f,
            x + 0.022f,
            y + eyeHalfH,
            0.125f,
            sr,
            sg,
            sb,
            1);
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
      box(poses, v, -0.118f, -0.118f, -0.118f, 0.118f, 0.118f, 0.118f, 0.032f, 0.042f, 0.058f, 1f);
      float er = pThink ? intensity : pSpeak ? intensity * 0.65f : pNap ? intensity * 0.14f : intensity * 0.22f;
      float eg = pThink ? intensity * 0.54f : pSpeak ? intensity * 0.96f : pNap ? intensity * 0.34f : intensity * 0.68f;
      float eb = pThink ? intensity * 0.10f : pSpeak ? intensity : pNap ? intensity * 0.72f : intensity;
      renderEdges(poses, v, 0.121f, 0.009f, er, eg, eb, 1.0f);
      renderCornerPods(poses, v, time, sleepBreath, pNap, pThink, "Wingman".equals(peer.state), er, eg, eb);
      renderSideWinglets(poses, v, time, pNap, er, eg, eb);
      renderHat(poses, v, peer.hat, time, intensity);

      float eyeHalfH = pNap ? 0.0035f : 0.022f;
      float sScale = pNap ? 0.26f : 1.0f;
      for (float x : SENSOR_OFFSETS)
        for (float y : SENSOR_OFFSETS)
          box(
              poses,
              v,
              x - 0.022f,
              y - eyeHalfH,
              0.119f,
              x + 0.022f,
              y + eyeHalfH,
              0.125f,
              intensity * 0.90f * sScale,
              intensity * 0.97f * sScale,
              intensity * sScale,
              1f);
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
   * 8 articulated corner pods that pull inward when sleeping and expand/breathe outward along the
   * corner diagonals when thinking or active.
   */
  private static void renderCornerPods(
      PoseStack p,
      VertexConsumer v,
      double time,
      double sleepBreath,
      boolean napping,
      boolean thinking,
      boolean wingman,
      float er,
      float eg,
      float eb) {
    int idx = 0;
    for (float sx : CORNER_SIGNS) {
      for (float sy : CORNER_SIGNS) {
        for (float sz : CORNER_SIGNS) {
          double phase = idx++ * 0.8;
          float pullOut;
          if (napping) {
            pullOut = (float) (0.002 + (sleepBreath + 1.0) * 0.0025);
          } else if (thinking) {
            pullOut = (float) (0.013 + (Math.sin(time * 5.2 + phase) + 1.0) * 0.0065);
          } else {
            pullOut = (float) (0.005 + (Math.sin(time * 1.8 + phase) + 1.0) * 0.003);
          }
          float sweepZ = (wingman && sz < 0) ? -0.012f : 0f;
          float cx = sx * (0.104f + pullOut);
          float cy = sy * (0.104f + pullOut);
          float cz = sz * (0.104f + pullOut) + sweepZ;
          float hs = 0.025f;
          // Dark metallic outer corner shell
          box(
              p,
              v,
              cx - hs,
              cy - hs,
              cz - hs,
              cx + hs,
              cy + hs,
              cz + hs,
              0.055f + er * 0.18f,
              0.070f + eg * 0.18f,
              0.095f + eb * 0.18f,
              1f);
          // Glowing inner energy core node visible when corner pods expand outward
          float cs = 0.013f;
          float icx = sx * (0.092f + pullOut * 0.45f);
          float icy = sy * (0.092f + pullOut * 0.45f);
          float icz = sz * (0.092f + pullOut * 0.45f);
          box(
              p,
              v,
              icx - cs,
              icy - cs,
              icz - cs,
              icx + cs,
              icy + cs,
              icz + cs,
              er * 0.9f,
              eg * 0.9f,
              eb * 0.9f,
              1f);
        }
      }
    }
  }

  /** Two articulated side sensor winglets/fins that breathe in and out on the cube's flanks. */
  private static void renderSideWinglets(
      PoseStack p, VertexConsumer v, double time, boolean folded, float er, float eg, float eb) {
    float span = folded ? 0.004f : (float) (0.014 + Math.sin(time * 2.1) * 0.005);
    float drop = folded ? -0.012f : 0.005f;
    // Left & right flank winglets
    box(
        p,
        v,
        -0.122f - span,
        -0.028f + drop,
        -0.045f,
        -0.118f,
        0.028f + drop,
        0.045f,
        er * 0.75f,
        eg * 0.75f,
        eb * 0.75f,
        1f);
    box(
        p,
        v,
        0.118f,
        -0.028f + drop,
        -0.045f,
        0.122f + span,
        0.028f + drop,
        0.045f,
        er * 0.75f,
        eg * 0.75f,
        eb * 0.75f,
        1f);
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
