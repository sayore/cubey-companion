package dev.qb2.ui.widgets;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class SecretBox extends EditBox {
  public SecretBox(Font font, int x, int y, int width, int height, Component label) {
    super(font, x, y, width, height, label);
    setFormatter(
        (text, cursor) -> Component.literal("•".repeat(text.length())).getVisualOrderText());
  }

  @Override
  protected MutableComponent createNarrationMessage() {
    return Component.literal("Secret field. Value hidden.");
  }
}
