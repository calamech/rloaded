package io.github.calamech.rloaded.testmods.keybindandtab;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A test mod that adds a creative tab, which players must see without restarting. */
public final class KeybindAndTab implements ModInitializer {
  /** The tab's id. */
  public static final ResourceKey<CreativeModeTab> TAB =
      ResourceKey.create(
          Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("keybind-and-tab", "tab"));

  @Override
  public void onInitialize() {
    Registry.register(
        BuiltInRegistries.CREATIVE_MODE_TAB,
        TAB,
        FabricCreativeModeTab.builder()
            .title(Component.translatable("itemGroup.keybind-and-tab.tab"))
            .icon(() -> new ItemStack(Items.DIAMOND))
            .displayItems((parameters, output) -> output.accept(Items.DIAMOND))
            .build());
  }
}
