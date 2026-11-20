package dev.qb2;

import dev.qb2.network.CubeyNetwork;
import dev.qb2.server.CubeyServerHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.LoggerFactory;

@Mod("qb2")
public final class QB2Mod {
  public QB2Mod(IEventBus modBus, ModContainer container) {
    modBus.addListener(CubeyNetwork::register);
    CubeyServerHelper.register();
    if (FMLEnvironment.dist.isClient()) {
      try {
        ClientBootstrap.init(modBus, container);
      } catch (NoClassDefFoundError e) {
        LoggerFactory.getLogger("qb2")
            .error(
                "[QB2] Server-only JAR (qb2-*-server.jar) was installed on a Minecraft client! "
                    + "Please replace it with qb2-*-client.jar to enable Cubey on the client.");
      }
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
