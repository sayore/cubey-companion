package dev.qb2.diagnostics;

import dev.qb2.perception.inventory.InventorySensor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Opt-in QA only: a real server-created chest, synced to the local client through vanilla menus.
 */
public final class InventoryClientProbe {
  private BlockPos chest;
  private boolean observedRecoveryCollision;

  public void recovery(dev.qb2.client.lifecycle.ClientController controller, int ticks) {
    var mc = Minecraft.getInstance();
    if (mc.player == null || mc.level == null || controller.session == null) return;
    var point = controller.session.motion.position();
    var box =
        new net.minecraft.world.phys.AABB(
            point.x() - .17,
            point.y() - .17,
            point.z() - .17,
            point.x() + .17,
            point.y() + .17,
            point.z() + .17);
    if (ticks == 100) {
      var pos = BlockPos.containing(point.x(), point.y(), point.z());
      var uuid = mc.player.getUUID();
      mc.getSingleplayerServer()
          .execute(
              () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                if (player != null)
                  player.serverLevel().setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
              });
    }
    if (ticks > 100 && ticks < 120 && !mc.level.noCollision(box)) observedRecoveryCollision = true;
    if (ticks == 130)
      System.out.println(
          "QB2_QA_RECOVERY_"
              + (observedRecoveryCollision && mc.level.noCollision(box) ? "OK" : "FAIL"));
  }

  public void tick(Minecraft mc, int ticks) {
    if (mc.getSingleplayerServer() == null || mc.player == null) return;
    var uuid = mc.player.getUUID();
    if (ticks == 25) {
      chest = mc.player.blockPosition().offset(2, 0, 0);
      var pos = chest;
      mc.getSingleplayerServer()
          .execute(
              () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                if (player == null) return;
                prepareArena(player);
                player.getInventory().setItem(20, new ItemStack(Items.IRON_INGOT, 7));
                player.getInventory().setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
                player.getInventory().setItem(40, new ItemStack(Items.TORCH, 3));
                player.inventoryMenu.broadcastChanges();
                player.serverLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
                if (player.serverLevel().getBlockEntity(pos) instanceof ChestBlockEntity box)
                  box.setItem(0, new ItemStack(Items.DIAMOND, 13));
              });
    }
    if (ticks == 40) {
      var pos = chest;
      mc.getSingleplayerServer()
          .execute(
              () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                if (player != null
                    && player.serverLevel().getBlockEntity(pos) instanceof ChestBlockEntity box)
                  player.openMenu(box);
              });
    }
    if (ticks == 55) {
      var view = InventorySensor.capture(mc);
      boolean backpack =
          view.player().stream()
              .anyMatch(
                  i ->
                      i.slot() == 20
                          && i.registryId().equals("minecraft:iron_ingot")
                          && i.count() == 7);
      boolean armor =
          view.player().stream()
              .anyMatch(
                  i ->
                      i.area().equals("armor") && i.registryId().equals("minecraft:diamond_boots"));
      boolean offhand =
          view.player().stream()
              .anyMatch(
                  i -> i.area().equals("offhand") && i.registryId().equals("minecraft:torch"));
      boolean container =
          view.container().stream()
              .anyMatch(i -> i.registryId().equals("minecraft:diamond") && i.count() == 13);
      System.out.println(
          "QB2_QA_INVENTORY_"
              + (backpack && armor && offhand && container ? "OK" : "FAIL")
              + " backpack="
              + backpack
              + " armor="
              + armor
              + " offhand="
              + offhand
              + " syncedContainer="
              + container);
    }
    if (ticks == 80) {
      mc.player.closeContainer();
      var view = InventorySensor.capture(mc);
      System.out.println(
          "QB2_QA_CLOSED_CONTAINER_"
              + (view.container().isEmpty() && view.title().isEmpty() ? "OK" : "FAIL"));
    }
  }

  private static void prepareArena(net.minecraft.server.level.ServerPlayer player) {
    var origin = player.blockPosition();
    for (int x = -5; x <= 5; x++)
      for (int z = -5; z <= 5; z++) {
        player
            .serverLevel()
            .setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        for (int y = 0; y <= 4; y++)
          player
              .serverLevel()
              .setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
      }
    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
    player.getAbilities().flying = true;
    player.onUpdateAbilities();
    player.teleportTo(origin.getX() + .5, origin.getY() + .1, origin.getZ() + .5);
    System.out.println("QB2_QA_STAGE_READY");
  }
}
