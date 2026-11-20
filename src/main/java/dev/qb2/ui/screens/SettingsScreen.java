package dev.qb2.ui.screens;

import com.google.gson.Gson;
import dev.qb2.client.config.Settings;
import dev.qb2.client.lifecycle.ClientController;
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
  private String message = "Changes apply after Save. Keys are masked and stored separately.";

  public SettingsScreen(Screen parent, ClientController controller) {
    super(Component.literal("QB2 settings"));
    this.parent = parent;
    this.controller = controller;
    draft =
        new Gson().fromJson(new Gson().toJson(controller.configuration.settings()), Settings.class);
    for (var f : fields)
      try {
        values.put(f.getName(), f.get(draft).toString());
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    values.put("apiKey", controller.configuration.apiKey());
    values.put("nerveKey", controller.configuration.nerveKey());
  }

  @Override
  protected void init() {
    edits.clear();
    labels.clear();
    var names = new ArrayList<>(List.of("apiKey", "nerveKey"));
    fields.forEach(f -> names.add(f.getName()));
    rows = Math.clamp((height - 145) / 26, 2, 8);
    page = Math.min(page, (names.size() - 1) / rows);
    int left = width / 2 - 145, y = 55;
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
            name.equals("apiKey") || name.equals("nerveKey")
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
            .bounds(left, height - 64, 40, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("→"),
                b -> {
                  page = (page + 1) % pages;
                  rebuildWidgets();
                })
            .bounds(left + 44, height - 64, 40, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("Test saved settings"),
                b -> {
                  controller.testConnection();
                  message =
                      controller.error.isBlank()
                          ? "Connection test queued; see the debug overlay in your world."
                          : controller.error;
                })
            .bounds(left + 90, height - 64, 200, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Save"), b -> save())
            .bounds(left, height - 38, 142, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(left + 148, height - 38, 142, 20)
            .build());
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
      controller.configuration.save(draft, values.get("apiKey"), values.get("nerveKey"));
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
    graphics.drawCenteredString(
        font,
        Component.literal("Page " + (page + 1) + " · K opens settings"),
        width / 2,
        32,
        0xBBBBBB);
    int y = 61;
    for (String label : labels) {
      graphics.drawString(font, label, width / 2 - 145, y, 0xFFFFFF);
      y += 26;
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
