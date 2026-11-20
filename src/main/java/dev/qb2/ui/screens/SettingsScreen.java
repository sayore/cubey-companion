package dev.qb2.ui.screens;

import com.google.gson.Gson;
import dev.qb2.client.config.Settings;
import dev.qb2.client.lifecycle.ClientController;
import dev.qb2.memory.Episode;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends Screen {
  private final Screen parent;
  private final ClientController controller;
  private final Settings draft;
  private final List<Field> fields = List.of(Settings.class.getFields());
  private final Map<String, String> values = new HashMap<>();
  private final Map<String, EditBox> edits = new HashMap<>();
  private final List<String> labels = new ArrayList<>();
  private int page;
  private int rows;
  private int presetIndex;
  private boolean inspectorMode;
  private String searchQuery = "";
  private List<Episode> inspectorResults = List.of();
  private String message = "Changes apply after Save. Keys are masked and stored separately.";

  public SettingsScreen(Screen parent, ClientController controller) {
    super(Component.translatable("screen.qb2.title"));
    this.parent = parent;
    this.controller = controller;
    draft =
        new Gson().fromJson(new Gson().toJson(controller.configuration.settings()), Settings.class);
    for (var f : fields)
      try {
        Object val = f.get(draft);
        values.put(f.getName(), val == null ? "" : val.toString());
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    values.put("apiKey", controller.configuration.apiKey());
    values.put("nerveKey", controller.configuration.nerveKey());
    values.put("fallbackApiKey", controller.configuration.fallbackApiKey());
  }

  @Override
  protected void init() {
    edits.clear();
    labels.clear();
    int left = width / 2 - 145;
    if (inspectorMode) {
      initInspector(left);
      return;
    }
    var names = new ArrayList<>(List.of("apiKey", "nerveKey", "fallbackApiKey"));
    fields.forEach(f -> names.add(f.getName()));
    rows = Math.clamp((height - 145) / 26, 2, 8);
    page = Math.min(page, (names.size() - 1) / rows);
    int y = 55;
    for (int i = page * rows; i < Math.min(names.size(), page * rows + rows); i++) {
      String name = names.get(i);
      labels.add(name);
      boolean bool = values.get(name).equals("true") || values.get(name).equals("false");
      if (bool) {
        addRenderableWidget(
            Button.builder(
                    Component.literal(values.get(name)),
                    b -> {
                      values.put(name, Boolean.toString(!Boolean.parseBoolean(values.get(name))));
                      b.setMessage(Component.literal(values.get(name)));
                    })
                .bounds(left + 125, y, 165, 20)
                .build());
      } else {
        EditBox edit =
            name.equals("apiKey") || name.equals("nerveKey") || name.equals("fallbackApiKey")
                ? new dev.qb2.ui.widgets.SecretBox(
                    font, left + 125, y, 165, 20, Component.literal(name))
                : new EditBox(font, left + 125, y, 165, 20, Component.literal(name));
        edit.setMaxLength(2048);
        edit.setValue(values.get(name));
        edit.setResponder(value -> values.put(name, value));
        edits.put(name, edit);
        addRenderableWidget(edit);
      }
      y += 26;
    }
    int pages = (names.size() + rows - 1) / rows;
    addRenderableWidget(
        Button.builder(
                Component.literal("←"),
                b -> {
                  page = Math.floorMod(page - 1, pages);
                  rebuildWidgets();
                })
            .bounds(left, height - 64, 28, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("→"),
                b -> {
                  page = (page + 1) % pages;
                  rebuildWidgets();
                })
            .bounds(left + 31, height - 64, 28, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("Preset"),
                b -> applyNextPreset())
            .bounds(left + 62, height - 64, 54, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.qb2.tab.inspector"),
                b -> {
                  inspectorMode = true;
                  runMemorySearch();
                  rebuildWidgets();
                })
            .bounds(left + 119, height - 64, 68, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.translatable("screen.qb2.test"),
                b -> {
                  controller.testConnection();
                  message =
                      controller.error.isBlank()
                          ? "Connection test queued; see the debug overlay in your world."
                          : controller.error;
                })
            .bounds(left + 190, height - 64, 100, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.save"), b -> save())
            .bounds(left, height - 38, 142, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.close"), b -> onClose())
            .bounds(left + 148, height - 38, 142, 20)
            .build());
  }

  private void initInspector(int left) {
    EditBox queryBox = new EditBox(font, left, 48, 210, 20, Component.literal("query"));
    queryBox.setMaxLength(200);
    queryBox.setValue(searchQuery);
    queryBox.setResponder(v -> searchQuery = v);
    addRenderableWidget(queryBox);
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.search"), b -> runMemorySearch())
            .bounds(left + 216, 48, 74, 20)
            .build());
    int maxVisible = Math.clamp((height - 140) / 24, 2, 7);
    int y = 76;
    for (int i = 0; i < Math.min(inspectorResults.size(), maxVisible); i++) {
      Episode ep = inspectorResults.get(i);
      String summary = "[" + ep.kind() + "] " + ep.text();
      labels.add(font.plainSubstrByWidth(summary, 240));
      addRenderableWidget(
          Button.builder(
                  Component.translatable("screen.qb2.delete"),
                  b -> {
                    b.active = false;
                    controller.deleteMemoryAsync(
                        ep.id(),
                        ok -> {
                          message = ok ? "Memory deleted." : "Delete failed; check NERVE bridge.";
                          runMemorySearch();
                        });
                  })
              .bounds(left + 246, y, 44, 20)
              .build());
      y += 24;
    }
    addRenderableWidget(
        Button.builder(
                Component.literal("← Settings"),
                b -> {
                  inspectorMode = false;
                  rebuildWidgets();
                })
            .bounds(left, height - 38, 142, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.close"), b -> onClose())
            .bounds(left + 148, height - 38, 142, 20)
            .build());
  }

  private void applyNextPreset() {
    int p = presetIndex++ % 3;
    if (p == 0) {
      values.put("provider", "google");
      values.put("endpoint", "https://generativelanguage.googleapis.com/v1beta");
      values.put("model", "gemma-4-26b-a4b-it");
      values.put("modelChain", "gemma-4-26b-a4b-it,gemma-4-31b-it,gemini-2.5-flash-lite");
      values.put("ambientModel", "gemma-4-26b-a4b-it");
      values.put("tokenBudget", "950");
      message = "Preset applied: Google Gemma 4 Slim (Save to activate).";
    } else if (p == 1) {
      values.put("provider", "google");
      values.put("endpoint", "https://generativelanguage.googleapis.com/v1beta");
      values.put("model", "gemma-4-31b-it");
      values.put("modelChain", "gemma-4-31b-it,gemma-4-26b-a4b-it,gemini-2.5-flash-lite");
      values.put("ambientModel", "gemma-4-26b-a4b-it");
      values.put("tokenBudget", "1050");
      message = "Preset applied: Google 31B Primary (Save to activate).";
    } else {
      values.put("provider", "openai");
      values.put("endpoint", "http://127.0.0.1:11434/v1");
      values.put("model", "gemma3:12b");
      values.put("modelChain", "gemma3:12b,gemma3:4b");
      values.put("ambientModel", "gemma3:4b");
      values.put("tokenBudget", "1200");
      message = "Preset applied: Local Ollama (Save to activate).";
    }
    rebuildWidgets();
  }

  private void runMemorySearch() {
    message = "Searching NERVE...";
    controller.searchMemoriesAsync(
        searchQuery,
        results -> {
          inspectorResults = results;
          message = results.isEmpty() ? "No memories found." : "Found " + results.size() + " memories.";
          if (inspectorMode) rebuildWidgets();
        });
  }

  private void save() {
    try {
      for (var f : fields) {
        String value = values.get(f.getName());
        if (f.getType() == boolean.class) f.setBoolean(draft, Boolean.parseBoolean(value));
        else if (f.getType() == int.class) f.setInt(draft, Integer.parseInt(value));
        else if (f.getType() == double.class) f.setDouble(draft, Double.parseDouble(value));
        else f.set(draft, value);
      }
      controller.configuration.save(
          draft, values.get("apiKey"), values.get("nerveKey"), values.get("fallbackApiKey"));
      controller.reconfigure();
      onClose();
    } catch (Exception e) {
      message =
          "Invalid setting or write failed. Use valid numbers, HTTPS/loopback URLs and"
              + " google/openai.";
    }
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    super.render(graphics, mouseX, mouseY, delta);
    graphics.drawCenteredString(font, title, width / 2, 15, 0xBBEEFF);
    String subtitle =
        inspectorMode
            ? "Memory Inspector · current world"
            : "Page " + (page + 1) + " · K opens settings";
    graphics.drawCenteredString(font, Component.literal(subtitle), width / 2, 32, 0xBBBBBB);
    int y = inspectorMode ? 82 : 61;
    int step = inspectorMode ? 24 : 26;
    for (String label : labels) {
      graphics.drawString(font, label, width / 2 - 145, y, 0xFFFFFF);
      y += step;
    }
    graphics.drawCenteredString(
        font,
        Component.literal(font.plainSubstrByWidth(message, width - 20)),
        width / 2,
        height - 80,
        0xBBBBBB);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
