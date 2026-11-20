package dev.qb2;

import dev.qb2.network.CubeyNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod("qb2")
public final class QB2Mod {
  public QB2Mod(IEventBus modBus, ModContainer container) {
    modBus.addListener(CubeyNetwork::register);
    if (FMLEnvironment.dist.isClient()) {
      ClientBootstrap.init(modBus, container);
    }
  }

  private static final class ClientBootstrap {
    static void init(IEventBus modBus, ModContainer container) {
      dev.qb2.diagnostics.ClientSmokeProbe.configureEnvironment();
      var controller = new dev.qb2.client.lifecycle.ClientController(modBus);
      container.registerExtensionPoint(
          net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
          (net.neoforged.neoforge.client.gui.IConfigScreenFactory)
              (mod, parent) -> new dev.qb2.ui.screens.SettingsScreen(parent, controller));
    }
  }
}
