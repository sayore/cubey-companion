package dev.qb2;

import dev.qb2.client.lifecycle.ClientController;
import dev.qb2.ui.screens.SettingsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = "qb2", dist = Dist.CLIENT)
public final class QB2Mod {
  public QB2Mod(IEventBus modBus, ModContainer container) {
    dev.qb2.diagnostics.ClientSmokeProbe.configureEnvironment();
    var controller = new ClientController(modBus);
    container.registerExtensionPoint(
        IConfigScreenFactory.class,
        (IConfigScreenFactory) (mod, parent) -> new SettingsScreen(parent, controller));
  }
}
