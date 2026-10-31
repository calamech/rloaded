package io.github.calamech.rloaded.testmods.keybindandtab;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Registers a key mapping in a category of its own, which players must see without restarting. */
public final class KeybindAndTabClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    KeyMapping.Category category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("keybind-and-tab", "test"));
    KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.keybind-and-tab.wave", InputConstants.KEY_G, category));
  }
}
