package dev.qb2.client.lifecycle;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Client-local /qb commands for quick companion control without server involvement. */
public final class QB2ClientCommands {
  private QB2ClientCommands() {}

  public static void register(RegisterClientCommandsEvent event, ClientController controller) {
    event
        .getDispatcher()
        .register(
            Commands.literal("qb")
                .then(
                    Commands.literal("status")
                        .executes(
                            ctx -> {
                              var s = controller.configuration.settings();
                              var m = controller.mind;
                              String model = m.activeModel.isBlank() ? s.model : m.activeModel;
                              String backoff =
                                  m.inCooldown()
                                      ? " | backoff=" + m.cooldownRemainingSeconds() + "s"
                                      : "";
                              send(
                                  "[QB2] state="
                                      + (controller.session == null
                                          ? "offline"
                                          : controller.session.state)
                                      + " | paused="
                                      + s.paused
                                      + " | model="
                                      + model
                                      + backoff
                                      + " | nerve="
                                      + m.memoryStatus
                                      + " | catalog="
                                      + controller.knowledge.status);
                              return 1;
                            }))
                .then(
                    Commands.literal("pause")
                        .executes(
                            ctx -> {
                              controller.setPaused(true);
                              return 1;
                            }))
                .then(
                    Commands.literal("resume")
                        .executes(
                            ctx -> {
                              controller.setPaused(false);
                              return 1;
                            }))
                .then(
                    Commands.literal("come")
                        .executes(
                            ctx -> {
                              controller.summonCompanion();
                              return 1;
                            }))
                .then(
                    Commands.literal("look")
                        .executes(
                            ctx -> {
                              controller.lookHereCompanion();
                              return 1;
                            }))
                .then(
                    Commands.literal("sit")
                        .executes(
                            ctx -> {
                              controller.sitCompanion();
                              return 1;
                            }))
                .then(
                    Commands.literal("nap")
                        .executes(
                            ctx -> {
                              controller.sitCompanion();
                              return 1;
                            }))
                .then(
                    Commands.literal("wake")
                        .executes(
                            ctx -> {
                              controller.wakeCompanion();
                              return 1;
                            }))
                .then(
                    Commands.literal("death")
                        .executes(
                            ctx -> {
                              controller.guideToDeath();
                              return 1;
                            }))
                .then(
                    Commands.literal("find")
                        .then(
                            Commands.argument("item", StringArgumentType.greedyString())
                                .executes(
                                    ctx -> {
                                      controller.guideToItemAsync(
                                          StringArgumentType.getString(ctx, "item"));
                                      return 1;
                                    })))
                .then(
                    Commands.literal("flip")
                        .executes(
                            ctx -> {
                              if (controller.session != null) {
                                controller.session.situational.triggerBarrelRoll();
                              }
                              return 1;
                            }))
                .then(
                    Commands.literal("hat")
                        .then(
                            Commands.argument("style", StringArgumentType.word())
                                .executes(
                                    ctx -> {
                                      String h = StringArgumentType.getString(ctx, "style").toLowerCase();
                                      if (controller.session != null) {
                                        controller.session.situational.forcedHat = h;
                                        send("[QB2] Hat set to: " + h + " (use 'auto' for situational hats)");
                                      }
                                      return 1;
                                    })))
                .then(
                    Commands.literal("search")
                        .then(
                            Commands.argument("query", StringArgumentType.greedyString())
                                .executes(
                                    ctx -> {
                                      String q = StringArgumentType.getString(ctx, "query");
                                      send("[QB2] Searching NERVE for: " + q);
                                      controller.searchMemoriesAsync(
                                          q,
                                          results -> {
                                            if (results.isEmpty()) {
                                              send("[QB2] No memories matched.");
                                              return;
                                            }
                                            for (var ep : results.stream().limit(5).toList()) {
                                              String text = ep.text();
                                              if (text.length() > 120)
                                                text = text.substring(0, 120) + "...";
                                              send("[QB2:" + ep.kind() + "] " + text);
                                            }
                                          });
                                      return 1;
                                    }))));
  }

  private static void send(String msg) {
    Minecraft.getInstance().gui.getChat().addMessage(Component.literal(msg));
  }
}
