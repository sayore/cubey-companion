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
  private static final List<String> TAB_AI =
      List.of(
          "apiKey",
          "nerveKey",
          "fallbackApiKey",
          "provider",
          "endpoint",
          "model",
          "modelChain",
          "ambientModel",
          "fallbackProvider",
          "fallbackEndpoint",
          "fallbackModel",
          "nerveEndpoint",
          "whisperEndpoint",
          "whisperLanguage");
  private static final List<String> TAB_BEHAVIOR =
      List.of(
          "autonomous",
          "smoothFollow",
          "multiplayerSync",
          "radialMenu",
          "paused",
          "serverChat",
          "exploreRadius",
          "perceptionRadius",
          "inferenceSeconds",
          "speechSeconds",
          "tokenBudget",
          "outputTokens",
          "timeoutSeconds",
          "memoryTopK",
          "triggers",
          "style");
  private static final List<String> TAB_VISUALS =
      List.of(
          "thoughts",
          "speech",
          "sounds",
          "soundVolume",
          "particles",
          "showTargetBeam",
          "glow",
          "bubbleOpacity",
          "fieldOfView",
          "debug");

  private record SliderSpec(double min, double max, boolean integer, String suffix) {}

  private static final Map<String, SliderSpec> SLIDERS =
      Map.ofEntries(
          Map.entry("soundVolume", new SliderSpec(0.0, 1.0, false, "")),
          Map.entry("glow", new SliderSpec(0.0, 1.0, false, "")),
          Map.entry("bubbleOpacity", new SliderSpec(0.1, 1.0, false, "")),
          Map.entry("exploreRadius", new SliderSpec(2.0, 20.0, false, " blk")),
          Map.entry("perceptionRadius", new SliderSpec(3.0, 24.0, false, " blk")),
          Map.entry("fieldOfView", new SliderSpec(30.0, 180.0, true, "°")),
          Map.entry("inferenceSeconds", new SliderSpec(5.0, 120.0, true, "s")),
          Map.entry("speechSeconds", new SliderSpec(10.0, 180.0, true, "s")),
          Map.entry("tokenBudget", new SliderSpec(800.0, 4000.0, true, " tok")),
          Map.entry("outputTokens", new SliderSpec(150.0, 1200.0, true, " tok")));

  private static final Map<String, String> FRIENDLY_LABELS =
      Map.ofEntries(
          Map.entry("apiKey", "API Key (Masked)"),
          Map.entry("nerveKey", "NERVE Token (Masked)"),
          Map.entry("fallbackApiKey", "Fallback Key (Masked)"),
          Map.entry("provider", "Provider (google/openai)"),
          Map.entry("endpoint", "API Endpoint"),
          Map.entry("model", "Primary Model"),
          Map.entry("modelChain", "Failover Model Chain"),
          Map.entry("ambientModel", "Ambient / Background Model"),
          Map.entry("fallbackProvider", "Fallback Provider"),
          Map.entry("fallbackEndpoint", "Fallback Endpoint"),
          Map.entry("fallbackModel", "Fallback Model (e.g. gemma3:1b)"),
          Map.entry("nerveEndpoint", "NERVE Bridge URL"),
          Map.entry("whisperEndpoint", "Whisper PTT Endpoint"),
          Map.entry("whisperLanguage", "Whisper Language (de/en/auto)"),
          Map.entry("autonomous", "Autonomous Curiosity"),
          Map.entry("smoothFollow", "Smooth Trail Follow"),
          Map.entry("multiplayerSync", "Multiplayer Cubey Sync"),
          Map.entry("radialMenu", "Quick Question Radial Menu"),
          Map.entry("paused", "Pause Companion"),
          Map.entry("serverChat", "Send to Public Server Chat"),
          Map.entry("exploreRadius", "Explore Radius"),
          Map.entry("perceptionRadius", "Vision Radius"),
          Map.entry("inferenceSeconds", "Background Think Interval"),
          Map.entry("speechSeconds", "Spontaneous Speech Cooldown"),
          Map.entry("tokenBudget", "Input Token Budget"),
          Map.entry("outputTokens", "Max Output Tokens"),
          Map.entry("timeoutSeconds", "HTTP Timeout (s)"),
          Map.entry("memoryTopK", "Memory Top-K Hits"),
          Map.entry("triggers", "Chat Wake Triggers"),
          Map.entry("style", "Personality / Style"),
          Map.entry("thoughts", "Show Thought Bubbles"),
          Map.entry("speech", "Show Speech Bubbles"),
          Map.entry("sounds", "Cubey Sounds (On/Off)"),
          Map.entry("soundVolume", "Cubey Sound Volume"),
          Map.entry("particles", "Hover Particles"),
          Map.entry("showTargetBeam", "Target Focus Beam"),
          Map.entry("glow", "Sensor & Edge Glow"),
          Map.entry("bubbleOpacity", "Bubble Background Opacity"),
          Map.entry("fieldOfView", "Sensor Field of View"),
          Map.entry("debug", "Debug HUD Overlay"));

  private final Screen parent;
  private final ClientController controller;
  private final Settings draft;
  private final List<Field> fields = List.of(Settings.class.getFields());
  private final Map<String, String> values = new HashMap<>();
  private final Map<String, EditBox> edits = new HashMap<>();
  private final List<String> labels = new ArrayList<>();
  private int tab; // 0 = AI, 1 = Behavior, 2 = Visuals, 3 = Inspector
  private int page;
  private int rows;
  private int presetIndex;
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
    int left = width / 2 - 150;

    // Top 4 Category Tabs
    String[] tabTitles = {
      "screen.qb2.tab.api", "screen.qb2.tab.behavior", "Visuals & Audio", "screen.qb2.tab.inspector"
    };
    for (int t = 0; t < 4; t++) {
      int tabIdx = t;
      Component label =
          t == 2 ? Component.literal("Visuals & Audio") : Component.translatable(tabTitles[t]);
      var btn =
          Button.builder(
                  label,
                  b -> {
                    tab = tabIdx;
                    page = 0;
                    if (tab == 3) runMemorySearch();
                    rebuildWidgets();
                  })
              .bounds(left + t * 76, 26, 73, 18)
              .build();
      btn.active = (tab != tabIdx);
      addRenderableWidget(btn);
    }

    if (tab == 3) {
      initInspector(left);
      return;
    }

    List<String> names =
        switch (tab) {
          case 0 -> TAB_AI;
          case 1 -> TAB_BEHAVIOR;
          default -> TAB_VISUALS;
        };

    rows = Math.clamp((height - 145) / 24, 2, 7);
    int pages = Math.max(1, (names.size() + rows - 1) / rows);
    page = Math.min(page, pages - 1);
    int y = 52;
    for (int i = page * rows; i < Math.min(names.size(), page * rows + rows); i++) {
      String name = names.get(i);
      labels.add(FRIENDLY_LABELS.getOrDefault(name, name));
      String current = values.getOrDefault(name, "");
      boolean bool = current.equals("true") || current.equals("false");
      if (bool) {
        addRenderableWidget(
            Button.builder(
                    Component.literal(Boolean.parseBoolean(current) ? "ON" : "OFF"),
                    b -> {
                      boolean next = !Boolean.parseBoolean(values.get(name));
                      values.put(name, Boolean.toString(next));
                      b.setMessage(Component.literal(next ? "ON" : "OFF"));
                    })
                .bounds(left + 135, y, 165, 20)
                .build());
      } else if (SLIDERS.containsKey(name)) {
        SliderSpec spec = SLIDERS.get(name);
        double parsedVal;
        try {
          parsedVal = Double.parseDouble(current);
        } catch (NumberFormatException e) {
          parsedVal = spec.min;
        }
        double norm = Math.clamp((parsedVal - spec.min) / (spec.max - spec.min), 0.0, 1.0);
        addRenderableWidget(
            new AbstractSliderButton(
                left + 135, y, 165, 20, Component.literal(formatSlider(parsedVal, spec)), norm) {
              @Override
              protected void updateMessage() {
                double v = spec.min + this.value * (spec.max - spec.min);
                setMessage(Component.literal(formatSlider(v, spec)));
              }

              @Override
              protected void applyValue() {
                double v = spec.min + this.value * (spec.max - spec.min);
                values.put(
                    name,
                    spec.integer
                        ? Integer.toString((int) Math.round(v))
                        : String.format(Locale.ROOT, "%.2f", v));
              }
            });
      } else {
        EditBox edit =
            name.equals("apiKey") || name.equals("nerveKey") || name.equals("fallbackApiKey")
                ? new dev.qb2.ui.widgets.SecretBox(
                    font, left + 135, y, 165, 20, Component.literal(name))
                : new EditBox(font, left + 135, y, 165, 20, Component.literal(name));
        edit.setMaxLength(2048);
        edit.setValue(current);
        edit.setResponder(value -> values.put(name, value));
        edits.put(name, edit);
        addRenderableWidget(edit);
      }
      y += 24;
    }

    addRenderableWidget(
        Button.builder(
                Component.literal("←"),
                b -> {
                  page = Math.floorMod(page - 1, pages);
                  rebuildWidgets();
                })
            .bounds(left, height - 62, 30, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal((page + 1) + "/" + pages),
                b -> {
                  page = (page + 1) % pages;
                  rebuildWidgets();
                })
            .bounds(left + 33, height - 62, 36, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("→"),
                b -> {
                  page = (page + 1) % pages;
                  rebuildWidgets();
                })
            .bounds(left + 72, height - 62, 30, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Preset"), b -> applyNextPreset())
            .bounds(left + 106, height - 62, 62, 20)
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
            .bounds(left + 172, height - 62, 128, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.save"), b -> save())
            .bounds(left, height - 38, 147, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.close"), b -> onClose())
            .bounds(left + 153, height - 38, 147, 20)
            .build());
  }

  private static String formatSlider(double v, SliderSpec spec) {
    return (spec.integer
            ? Integer.toString((int) Math.round(v))
            : String.format(Locale.ROOT, "%.2f", v))
        + spec.suffix;
  }

  private void initInspector(int left) {
    EditBox queryBox = new EditBox(font, left, 50, 220, 20, Component.literal("query"));
    queryBox.setMaxLength(200);
    queryBox.setValue(searchQuery);
    queryBox.setResponder(v -> searchQuery = v);
    addRenderableWidget(queryBox);
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.search"), b -> runMemorySearch())
            .bounds(left + 226, 50, 74, 20)
            .build());
    int maxVisible = Math.clamp((height - 140) / 24, 2, 7);
    int y = 78;
    for (int i = 0; i < Math.min(inspectorResults.size(), maxVisible); i++) {
      Episode ep = inspectorResults.get(i);
      String summary = "[" + ep.kind() + "] " + ep.text();
      labels.add(font.plainSubstrByWidth(summary, 245));
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
              .bounds(left + 256, y, 44, 20)
              .build());
      y += 24;
    }
    addRenderableWidget(
        Button.builder(
                Component.literal("← Settings"),
                b -> {
                  tab = 0;
                  rebuildWidgets();
                })
            .bounds(left, height - 38, 147, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.qb2.close"), b -> onClose())
            .bounds(left + 153, height - 38, 147, 20)
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
      values.put("fallbackProvider", "openai");
      values.put("fallbackEndpoint", "http://127.0.0.1:11434/v1");
      values.put("fallbackModel", "gemma3:1b");
      values.put("tokenBudget", "950");
      message = "Preset applied: Google Gemma 4 + Local 1B VRAM Fallback (Save to activate).";
    } else if (p == 1) {
      values.put("provider", "google");
      values.put("endpoint", "https://generativelanguage.googleapis.com/v1beta");
      values.put("model", "gemma-4-31b-it");
      values.put("modelChain", "gemma-4-31b-it,gemma-4-26b-a4b-it,gemini-2.5-flash-lite");
      values.put("ambientModel", "gemma-4-26b-a4b-it");
      values.put("fallbackProvider", "openai");
      values.put("fallbackEndpoint", "http://127.0.0.1:11434/v1");
      values.put("fallbackModel", "gemma3:1b");
      values.put("tokenBudget", "1050");
      message = "Preset applied: Google 31B + Local 1B VRAM Fallback (Save to activate).";
    } else {
      values.put("provider", "openai");
      values.put("endpoint", "http://127.0.0.1:11434/v1");
      values.put("model", "gemma3:1b");
      values.put("modelChain", "gemma3:1b");
      values.put("ambientModel", "gemma3:1b");
      values.put("tokenBudget", "950");
      if (values.getOrDefault("apiKey", "").isBlank()) values.put("apiKey", "ollama");
      message = "Preset applied: 100% Local Ollama gemma3:1b in VRAM (Save to activate).";
    }
    rebuildWidgets();
  }

  private void runMemorySearch() {
    message = "Searching NERVE...";
    controller.searchMemoriesAsync(
        searchQuery,
        results -> {
          inspectorResults = results;
          message =
              results.isEmpty() ? "No memories found." : "Found " + results.size() + " memories.";
          if (tab == 3) rebuildWidgets();
        });
  }

  private void save() {
    try {
      for (var f : fields) {
        String value = values.get(f.getName());
        if (f.getType() == boolean.class) f.setBoolean(draft, Boolean.parseBoolean(value));
        else if (f.getType() == int.class) f.setInt(draft, (int) Math.round(Double.parseDouble(value)));
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
    graphics.drawCenteredString(font, title, width / 2, 10, 0xBBEEFF);
    int y = tab == 3 ? 84 : 58;
    for (String label : labels) {
      graphics.drawString(font, label, width / 2 - 150, y, 0xFFFFFF);
      y += 24;
    }
    graphics.drawCenteredString(
        font,
        Component.literal(font.plainSubstrByWidth(message, width - 20)),
        width / 2,
        height - 78,
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
