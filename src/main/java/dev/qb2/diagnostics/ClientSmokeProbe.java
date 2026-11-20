package dev.qb2.diagnostics;

import dev.qb2.client.lifecycle.ClientController;
import dev.qb2.ui.screens.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Explicit opt-in developer smoke run, in Gradle's separate run-smoke directory. */
public final class ClientSmokeProbe {
  private boolean started, finished;
  private int ticks;
  private boolean captured;
  private int settingsTicks;
  private final InventoryClientProbe inventoryProbe = new InventoryClientProbe();
  private final FlightClientProbe flightProbe = new FlightClientProbe();
  private long startup = System.nanoTime();

  public static void configureEnvironment() {
    if (Boolean.getBoolean("qb2.smoke") && Boolean.getBoolean("qb2.smoke.pack")) {
      // Production packs do not load dev-only GameTests referencing optional integrations.
      net.minecraft.SharedConstants.IS_RUNNING_IN_IDE = false;
      System.setProperty("neoforge.enableGameTest", "false");
      System.out.println(
          "QB2_QA_PACK_ENV: production-style GameTest registration for isolated modpack QA");
    }
  }

  public void tick(ClientController controller) {
    if (!Boolean.getBoolean("qb2.smoke") || finished) return;
    var mc = Minecraft.getInstance();
    if (mc.screen instanceof AccessibilityOnboardingScreen) mc.screen.onClose();
    if (!started && mc.screen instanceof TitleScreen) {
      started = true;
      System.out.println("QB2_SMOKE_TITLE_OK");
      mc.options.pauseOnLostFocus = false;
      mc.createWorldOpenFlows()
          .createFreshLevel(
              "QB2-smoke-" + System.currentTimeMillis(),
              new LevelSettings(
                  "QB2 smoke",
                  GameType.CREATIVE,
                  false,
                  Difficulty.PEACEFUL,
                  false,
                  new GameRules(),
                  WorldDataConfiguration.DEFAULT),
              new WorldOptions(4321L, false, false),
              WorldPresets::createNormalWorldDimensions,
              mc.screen);
    }
    if (controller.session != null && mc.player != null) {
      ticks++;
      if (ticks % 200 == 0)
        System.out.println("QB2_QA_KNOWLEDGE_PROGRESS " + controller.knowledge.status);
      inventoryProbe.tick(mc, ticks);
      inventoryProbe.recovery(controller, ticks);
      if (ticks == 20) {
        mc.setScreen(null);
        mc.player.setYRot(-55);
        mc.player.setXRot(5);
      }
      if (ticks == 60) {
        NeoForge.EVENT_BUS.post(
            new ClientChatEvent(
                "@qb Welche Gegenstände und Mengen siehst du in meinem Inventar und in der"
                    + " gerade geöffneten Truhe? Antworte kurz."));
        System.out.println("QB2_SMOKE_CHAT_DISPATCHED");
      }
      boolean live = Boolean.getBoolean("qb2.smoke.live");
      if (ticks == 90 && controller.mind.busy()) FlightClientProbe.capture(mc, "qa-thinking.png");
      if (!captured
          && ticks >= 140
          && (!live
              || controller.mind.status.equals("ready")
              || controller.mind.status.equals("error"))) {
        captured = true;
        FlightClientProbe.aim(mc, controller.session.motion.position());
        FlightClientProbe.capture(mc, "qa-world.png");
        System.out.println(
            "QB2_SMOKE_WORLD_OK observations="
                + controller.session.observations.size()
                + " action="
                + controller.lastAction
                + " api="
                + controller.mind.status
                + " error="
                + controller.error);
        controller.configuration.settings().debug = false;
      }
      if (captured
          && !controller.mind.busy()
          && settingsTicks == 0
          && flightProbe.tick(controller)) {
        mc.setScreen(new SettingsScreen(null, controller));
        settingsTicks = 1;
      }
      if (settingsTicks > 0
          && ++settingsTicks > 40
          && (!Boolean.parseBoolean(System.getProperty("qb2.smoke.waitCatalog", "true"))
              || controller.knowledge.status.startsWith("ready:"))) {
        System.out.println(
            (controller.knowledge.status.startsWith("ready:")
                    ? "QB2_QA_KNOWLEDGE_OK "
                    : "QB2_QA_KNOWLEDGE_SKIPPED ")
                + controller.knowledge.status);
        FlightClientProbe.capture(mc, "qa-settings.png");
        System.out.println("QB2_SMOKE_CONFIG_OK");
        finished = true;
        mc.stop();
      }
    }
    if ((System.nanoTime() - startup) / 1e9 > Integer.getInteger("qb2.smoke.timeout", 720)) {
      System.out.println("QB2_SMOKE_TIMEOUT");
      finished = true;
      mc.stop();
    }
  }
}
