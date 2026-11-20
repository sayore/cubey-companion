package dev.qb2.perception.world;

import java.util.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Mod-agnostic connected-component analyzer for Minecraft tech contraptions (Create, AE2, Mekanism,
 * Pipez, Farmer's Delight, Vanilla redstone/hoppers, etc.). Traces ONLY connected machine,
 * logistics, container, and catalyst blocks — never a blind terrain cube — and reads client-synced
 * BlockEntity state (held items, fluids, heat, fan-through-catalyst flow) so Cubey can deduce what
 * a contraption is built to do.
 */
public final class MachineGraphAnalyzer {
  private static final int MAX_BLOCKS = 42;
  private static final int MAX_RADIUS = 8;

  private static final Set<String> TECH_MODS =
      Set.of(
          "create",
          "createaddition",
          "create_enchantment_industry",
          "create_connected",
          "create_sa",
          "ae2",
          "mekanism",
          "mekanismgenerators",
          "pipez",
          "laserio",
          "xnet",
          "enderio",
          "thermal",
          "industrialforegoing",
          "immersiveengineering",
          "modularrouters",
          "fluxnetworks",
          "powah",
          "refinedstorage",
          "functionalstorage",
          "sophisticatedstorage",
          "oritech",
          "modern_industrialization",
          "pneumaticcraft",
          "ars_nouveau",
          "cookingforblockheads",
          "farmersdelight");

  private static final List<String> MACHINE_KEYWORDS =
      List.of(
          "depot",
          "belt",
          "chute",
          "funnel",
          "fan",
          "press",
          "mixer",
          "basin",
          "crushing",
          "millstone",
          "saw",
          "drill",
          "deployer",
          "mechanical",
          "cogwheel",
          "shaft",
          "gearbox",
          "pipe",
          "cable",
          "conduit",
          "hopper",
          "furnace",
          "smoker",
          "crafter",
          "dropper",
          "dispenser",
          "observer",
          "chest",
          "barrel",
          "drawer",
          "tank",
          "burner",
          "catalyst",
          "terminal",
          "controller",
          "drive",
          "interface",
          "importer",
          "exporter",
          "bus",
          "generator",
          "reactor",
          "crusher",
          "enrichment",
          "infuser",
          "smelter",
          "cooking_pot",
          "cooking_table",
          "fridge",
          "oven",
          "sink");

  public record MachineGraph(
      String anchorBlock,
      dev.qb2.companion.movement.Point centroid,
      int totalBlocks,
      Map<String, Integer> componentCounts,
      List<String> flows,
      List<String> observedContents,
      String summary) {}

  private MachineGraphAnalyzer() {}

  public static boolean isMachineOrLogistics(ClientLevel level, BlockPos pos) {
    if (level == null || pos == null || !level.hasChunkAt(pos)) return false;
    BlockState state = level.getBlockState(pos);
    if (state.isAir()) return false;
    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    if (isCatalystBlock(state, id)) return true;
    if (level.getBlockEntity(pos) != null && !id.endsWith("sign") && !id.endsWith("bed")) {
      return true;
    }
    return matchesMachineId(id);
  }

  public static boolean matchesMachineId(String blockId) {
    if (blockId == null || blockId.isBlank()) return false;
    int colon = blockId.indexOf(':');
    String mod = colon > 0 ? blockId.substring(0, colon) : "minecraft";
    String path = colon > 0 ? blockId.substring(colon + 1) : blockId;
    if (TECH_MODS.contains(mod)) {
      // Skip purely decorative building blocks inside tech mods (e.g. create:cut_limestone_bricks)
      if (path.endsWith("_bricks")
          || path.endsWith("_tiles")
          || path.endsWith("_stairs")
          || path.endsWith("_slab")
          || path.endsWith("_wall")
          || path.endsWith("_window")
          || path.endsWith("_planks")) {
        return false;
      }
      return true;
    }
    for (String kw : MACHINE_KEYWORDS) {
      if (path.contains(kw)) return true;
    }
    return false;
  }

  private static boolean isCatalystBlock(BlockState state, String id) {
    return state.is(Blocks.WATER)
        || state.is(Blocks.LAVA)
        || state.is(Blocks.CAMPFIRE)
        || state.is(Blocks.SOUL_CAMPFIRE)
        || state.is(Blocks.FIRE)
        || state.is(Blocks.SOUL_FIRE)
        || id.contains("catalyst")
        || id.contains("blaze_burner");
  }

  /**
   * Traces the connected machine assembly around {@code seed}. Returns {@code null} if neither
   * {@code seed} nor its immediate neighbors belong to a machine/logistics assembly.
   */
  public static MachineGraph analyze(ClientLevel level, BlockPos seed) {
    if (level == null || seed == null || !level.hasChunkAt(seed)) return null;
    BlockPos start = seed;
    if (!isMachineOrLogistics(level, start)) {
      start = null;
      for (Direction d : Direction.values()) {
        BlockPos n = seed.relative(d);
        if (isMachineOrLogistics(level, n)) {
          start = n;
          break;
        }
      }
      if (start == null) return null;
    }

    String anchorId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(start).getBlock()).toString();
    Map<String, Integer> counts = new LinkedHashMap<>();
    Set<String> flows = new LinkedHashSet<>();
    Set<String> contents = new LinkedHashSet<>();

    Set<BlockPos> visited = new HashSet<>();
    ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    visited.add(start);
    queue.add(start);

    while (!queue.isEmpty() && visited.size() < MAX_BLOCKS) {
      BlockPos cur = queue.poll();
      BlockState st = level.getBlockState(cur);
      String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
      String shortId = shortName(id);
      counts.merge(id, 1, Integer::sum);

      // 1. Inspect client-synced BlockEntity contents (Depot HeldItem, Basin items, fluids, heat!)
      BlockEntity be = level.getBlockEntity(cur);
      if (be != null && contents.size() < 10) {
        extractBlockEntityClues(level, be, shortId, contents);
      }

      // 2. Detect Create Fan / Air-Stream through Catalyst -> Depot/Belt!
      if (id.contains("fan")) {
        Direction facing = extractFacing(st);
        if (facing != null) {
          detectFanProcessingLine(level, cur, facing, flows, visited, queue, start);
        }
      }

      // 3. Detect directional transfer edges (Funnels, Chutes, Hoppers, Pipes, Belts)
      if (id.contains("funnel")
          || id.contains("chute")
          || id.contains("hopper")
          || id.contains("pipe")
          || id.contains("importer")
          || id.contains("exporter")) {
        detectLocalEdges(level, cur, st, shortId, flows);
      }

      // 4. Expand BFS only into connected machine/logistics/catalyst blocks within MAX_RADIUS
      for (Direction dir : Direction.values()) {
        BlockPos next = cur.relative(dir);
        if (visited.contains(next)) continue;
        if (next.distManhattan(start) > MAX_RADIUS * 2) continue;
        if (!level.hasChunkAt(next)) continue;
        if (!isMachineOrLogistics(level, next)) continue;
        visited.add(next);
        queue.add(next);
        if (visited.size() >= MAX_BLOCKS) break;
      }
    }

    if (visited.size() <= 1 && contents.isEmpty() && flows.isEmpty()) {
      return null;
    }

    List<String> flowList = flows.stream().limit(10).toList();
    List<String> contentList = contents.stream().limit(10).toList();

    double sx = 0, sy = 0, sz = 0;
    for (BlockPos bp : visited) {
      sx += bp.getX() + 0.5;
      sy += bp.getY() + 0.5;
      sz += bp.getZ() + 0.5;
    }
    var centroid =
        new dev.qb2.companion.movement.Point(
            sx / visited.size(), sy / visited.size(), sz / visited.size());

    StringBuilder sb = new StringBuilder();
    sb.append("Connected assembly (").append(visited.size()).append(" blocks around ").append(shortName(anchorId)).append("): ");
    int shown = 0;
    for (var e : counts.entrySet()) {
      if (shown++ >= 10) break;
      if (shown > 1) sb.append(", ");
      sb.append(e.getValue()).append("x ").append(e.getKey());
    }
    if (!flowList.isEmpty()) {
      sb.append(" | Flows: ").append(String.join("; ", flowList));
    }
    if (!contentList.isEmpty()) {
      sb.append(" | Live contents/state: ").append(String.join("; ", contentList));
    }
    return new MachineGraph(
        anchorId, centroid, visited.size(), counts, flowList, contentList, sb.toString());
  }

  private static void detectFanProcessingLine(
      ClientLevel level,
      BlockPos fanPos,
      Direction facing,
      Set<String> flows,
      Set<BlockPos> visited,
      ArrayDeque<BlockPos> queue,
      BlockPos origin) {
    String catalyst = null;
    for (int dist = 1; dist <= 5; dist++) {
      BlockPos p = fanPos.relative(facing, dist);
      if (!level.hasChunkAt(p)) break;
      BlockState st = level.getBlockState(p);
      String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
      if (st.is(Blocks.WATER) || id.contains("water")) {
        catalyst = "water (Bulk Washing)";
      } else if (st.is(Blocks.LAVA) || id.contains("lava")) {
        catalyst = "lava (Bulk Smelting)";
      } else if (st.is(Blocks.SOUL_CAMPFIRE) || st.is(Blocks.SOUL_FIRE) || id.contains("haunting")) {
        catalyst = "soul_fire (Bulk Haunting)";
      } else if (st.is(Blocks.CAMPFIRE) || st.is(Blocks.FIRE) || id.contains("smoking")) {
        catalyst = "campfire (Bulk Smoking)";
      } else if (id.contains("catalyst")) {
        catalyst = shortName(id);
      } else if (id.contains("depot") || id.contains("belt") || id.contains("basin")) {
        String edge =
            catalyst != null
                ? "encased_fan --(" + catalyst + ")--> " + shortName(id)
                : "encased_fan --> " + shortName(id);
        flows.add(edge);
        if (!visited.contains(p) && visited.size() < MAX_BLOCKS && p.distManhattan(origin) <= MAX_RADIUS * 2) {
          visited.add(p);
          queue.add(p);
        }
        break;
      } else if (!st.isAir() && !id.contains("fan")) {
        break;
      }
    }
  }

  private static void detectLocalEdges(
      ClientLevel level, BlockPos pos, BlockState st, String shortId, Set<String> flows) {
    if (flows.size() >= 10) return;
    List<String> neighbors = new ArrayList<>();
    for (Direction dir : Direction.values()) {
      BlockPos n = pos.relative(dir);
      if (!level.hasChunkAt(n)) continue;
      BlockState ns = level.getBlockState(n);
      if (ns.isAir()) continue;
      String nid = BuiltInRegistries.BLOCK.getKey(ns.getBlock()).toString();
      if (nid.contains("chest")
          || nid.contains("barrel")
          || nid.contains("drawer")
          || nid.contains("depot")
          || nid.contains("belt")
          || nid.contains("basin")
          || nid.contains("furnace")
          || nid.contains("tank")
          || nid.contains("interface")) {
        neighbors.add(shortName(nid));
      }
    }
    if (neighbors.size() >= 2) {
      flows.add(neighbors.get(0) + " <-> " + shortId + " <-> " + neighbors.get(1));
    } else if (neighbors.size() == 1) {
      flows.add(shortId + " <-> " + neighbors.get(0));
    }
  }

  private static void extractBlockEntityClues(
      ClientLevel level, BlockEntity be, String shortBlock, Set<String> out) {
    try {
      CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
      // Create Depot / Funnel / Belt held item
      if (tag.contains("HeldItem", Tag.TAG_COMPOUND)) {
        CompoundTag held = tag.getCompound("HeldItem");
        String item = extractItemFromTag(held.contains("Item", Tag.TAG_COMPOUND) ? held.getCompound("Item") : held);
        if (!item.isBlank()) out.add(shortBlock + " holds " + item);
      }
      if (tag.contains("Item", Tag.TAG_COMPOUND)) {
        String item = extractItemFromTag(tag.getCompound("Item"));
        if (!item.isBlank()) out.add(shortBlock + " has " + item);
      }
      if (tag.contains("Filter", Tag.TAG_COMPOUND)) {
        String item = extractItemFromTag(tag.getCompound("Filter"));
        if (!item.isBlank()) out.add(shortBlock + " filter=" + item);
      }
      // Standard container / Basin Items list
      if (tag.contains("Items", Tag.TAG_LIST)) {
        ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < Math.min(4, list.size()); i++) {
          String item = extractItemFromTag(list.getCompound(i));
          if (!item.isBlank() && !found.contains(item)) found.add(item);
        }
        if (!found.isEmpty()) out.add(shortBlock + " items=[" + String.join(", ", found) + "]");
      }
      // Fluids (Create Tanks / Basins / Mekanism)
      if (tag.contains("TankContent", Tag.TAG_COMPOUND)) {
        CompoundTag tank = tag.getCompound("TankContent");
        String fluid = tank.getString("id");
        if (fluid.isBlank()) fluid = tank.getString("FluidName");
        int amt = tank.getInt("amount");
        if (amt == 0) amt = tank.getInt("Amount");
        if (!fluid.isBlank() && amt > 0) out.add(shortBlock + " fluid=" + fluid + " (" + amt + "mB)");
      }
      // Create Kinetic Speed (RPM)
      if (tag.contains("Speed")) {
        float rpm = tag.getFloat("Speed");
        if (Math.abs(rpm) > 0.5f) out.add(shortBlock + " speed=" + Math.round(Math.abs(rpm)) + "RPM");
      }
    } catch (Exception ignored) {
    }
  }

  private static String extractItemFromTag(CompoundTag tag) {
    if (tag == null) return "";
    String id = tag.getString("id");
    if (id.isBlank()) return "";
    int count = tag.contains("count") ? tag.getInt("count") : tag.getInt("Count");
    return count > 1 ? (count + "x " + id) : id;
  }

  private static Direction extractFacing(BlockState state) {
    if (state.hasProperty(BlockStateProperties.FACING)) {
      return state.getValue(BlockStateProperties.FACING);
    }
    if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
      return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }
    return null;
  }

  private static String shortName(String id) {
    int idx = id.indexOf(':');
    return idx >= 0 ? id.substring(idx + 1) : id;
  }
}
