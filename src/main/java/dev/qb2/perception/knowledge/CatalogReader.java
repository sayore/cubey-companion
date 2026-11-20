package dev.qb2.perception.knowledge;

import dev.qb2.memory.knowledge.KnowledgeFact;
import dev.qb2.perception.Keywords;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Iterated only on the client thread. No registry/world objects escape into background work. */
public final class CatalogReader {
  private final Minecraft mc;
  private Iterator<?> entries;
  private int phase;
  public int partialRecipes;

  public CatalogReader(Minecraft mc) {
    this.mc = mc;
    entries = BuiltInRegistries.BLOCK.iterator();
  }

  public boolean hasNext() {
    while (!entries.hasNext() && phase < 3) {
      phase++;
      entries =
          switch (phase) {
            case 1 -> BuiltInRegistries.ITEM.iterator();
            case 2 -> BuiltInRegistries.ENTITY_TYPE.iterator();
            default -> mc.level.getRecipeManager().getRecipes().iterator();
          };
    }
    return entries.hasNext();
  }

  public KnowledgeFact next() {
    Object value = entries.next();
    return switch (phase) {
      case 0 -> {
        var block = (net.minecraft.world.level.block.Block) value;
        yield simple(
            "block",
            BuiltInRegistries.BLOCK.getKey(block).toString(),
            block.getName().getString()
                + "; default hardness="
                + block.defaultDestroyTime()
                + "; blast resistance="
                + block.getExplosionResistance()
                + "; correct tool required="
                + block.defaultBlockState().requiresCorrectToolForDrops());
      }
      case 1 -> {
        var item = (net.minecraft.world.item.Item) value;
        yield simple(
            "item",
            BuiltInRegistries.ITEM.getKey(item).toString(),
            item.getDescription().getString()
                + "; default max stack="
                + item.getDefaultInstance().getMaxStackSize()
                + "; default max durability="
                + item.getDefaultInstance().getMaxDamage());
      }
      case 2 -> {
        var entity = (net.minecraft.world.entity.EntityType<?>) value;
        yield simple(
            "entity",
            BuiltInRegistries.ENTITY_TYPE.getKey(entity).toString(),
            entity.getDescription().getString()
                + "; category="
                + entity.getCategory().getName()
                + "; dimensions="
                + entity.getWidth()
                + "x"
                + entity.getHeight()
                + "; fire immune="
                + entity.fireImmune());
      }
      default -> recipe((RecipeHolder<?>) value);
    };
  }

  private KnowledgeFact simple(String kind, String id, String name) {
    var words = new LinkedHashSet<>(Keywords.extract(id + " " + name));
    words.add(kind);
    return new KnowledgeFact(
        kind, id, List.copyOf(words), "Client registry " + kind + " " + id + ": " + name);
  }

  private KnowledgeFact recipe(RecipeHolder<?> holder) {
    var recipe = holder.value();
    String id = holder.id().toString();
    var words = new LinkedHashSet<String>();
    words.add(id);
    words.add("recipe");
    StringBuilder text =
        new StringBuilder("Server-synced recipe ")
            .append(id)
            .append("; type=")
            .append(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    boolean partial = false;
    try {
      var result = recipe.getResultItem(mc.level.registryAccess());
      if (result.isEmpty()) partial = true;
      else {
        String output = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        words.add(output);
        text.append("; output=").append(output).append(" x").append(result.getCount());
      }
      var ingredients = recipe.getIngredients();
      if (ingredients.isEmpty()) partial = true;
      text.append("; ingredient slots (alternatives separated by |): ");
      int slots = 0;
      for (var ingredient : ingredients) {
        if (++slots > 24 || text.length() > 1300) {
          partial = true;
          break;
        }
        var alternatives = ingredient.getItems();
        if (alternatives.length > 8 || alternatives.length == 0) partial = true;
        text.append('[');
        for (int i = 0; i < Math.min(8, alternatives.length); i++) {
          String item = BuiltInRegistries.ITEM.getKey(alternatives[i].getItem()).toString();
          words.add(item);
          if (i > 0) text.append('|');
          text.append(item);
        }
        text.append(']');
      }
      // Generic Recipe APIs do not describe shape, fluids, catalysts, energy, or custom conditions.
      partial =
          partial
              || !BuiltInRegistries.RECIPE_TYPE
                  .getKey(recipe.getType())
                  .getNamespace()
                  .equals("minecraft");
    } catch (RuntimeException | LinkageError exception) {
      partial = true; // Mod-specific dynamic recipes may not support generic extraction.
    }
    if (text.length() > 1750) {
      text.setLength(1750);
      partial = true;
    }
    if (partial) partialRecipes++;
    text.append(
        partial
            ? "; PARTIAL: consult recipe viewer for full requirements."
            : "; See recipe viewer for arrangement and conditions.");
    return new KnowledgeFact("recipe", id, List.copyOf(words), text.toString());
  }
}
