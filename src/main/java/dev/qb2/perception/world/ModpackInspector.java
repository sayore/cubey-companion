package dev.qb2.perception.world;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Read-only, client-safe inspection of synced NeoForge capabilities (FE energy / fluids) and
 * optional modpack viewer presence (EMI/JEI/REI/Jade/WTHIT). Never invents unsynced server data.
 */
public final class ModpackInspector {
  private static final List<String> KNOWN_VIEWER_MODS = List.of("emi", "jei", "rei", "jade", "wthit");

  private ModpackInspector() {}

  public static String describeClientCapabilities(Level level, BlockPos pos, BlockState state) {
    if (level == null || pos == null || state == null) return "";
    var parts = new ArrayList<String>();
    try {
      var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, state, null, null);
      if (energy != null && energy.getMaxEnergyStored() > 0) {
        parts.add(
            "clientEnergy=" + energy.getEnergyStored() + "/" + energy.getMaxEnergyStored() + "FE");
      }
    } catch (Exception ignored) {
      /* Client-only capability may be absent or unsupported for this block. */
    }
    try {
      var fluid = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, state, null, null);
      if (fluid != null && fluid.getTanks() > 0) {
        int tanks = Math.min(fluid.getTanks(), 2);
        for (int i = 0; i < tanks; i++) {
          var stack = fluid.getFluidInTank(i);
          if (!stack.isEmpty()) {
            parts.add(
                "clientFluid="
                    + stack.getHoverName().getString()
                    + ":"
                    + stack.getAmount()
                    + "/"
                    + fluid.getTankCapacity(i)
                    + "mB");
          }
        }
      }
    } catch (Exception ignored) {
      /* Client-only capability may be absent or unsupported for this block. */
    }
    return parts.isEmpty() ? "" : " " + String.join(" ", parts);
  }

  public static List<String> detectedViewerMods() {
    var found = new ArrayList<String>();
    try {
      var list = ModList.get();
      if (list == null) return found;
      for (String id : KNOWN_VIEWER_MODS) {
        if (list.isLoaded(id)) found.add(id);
      }
    } catch (Exception ignored) {
      /* Outside NeoForge runtime (e.g. unit tests). */
    }
    return found;
  }
}
