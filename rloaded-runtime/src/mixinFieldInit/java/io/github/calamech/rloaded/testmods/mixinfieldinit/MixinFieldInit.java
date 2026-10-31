package io.github.calamech.rloaded.testmods.mixinfieldinit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A test mod whose mixin adds an initialized field to every entity. Its {@code /fieldinit} command
 * counts the server's entities that lack the field: entities that existed before the mod was added
 * have it only if they were rebuilt.
 */
public final class MixinFieldInit implements ModInitializer {
  private static final Logger LOGGER = LoggerFactory.getLogger("mixin-field-init");

  @Override
  public void onInitialize() {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, context, selection) ->
            dispatcher.register(
                Commands.literal("fieldinit")
                    .executes(
                        command -> {
                          int entities = 0;
                          int missing = 0;
                          for (ServerLevel level : command.getSource().getServer().getAllLevels()) {
                            for (Entity entity : level.getAllEntities()) {
                              entities++;
                              if (((FieldInitMarker) entity).mixinFieldInit$marker() == null) {
                                missing++;
                              }
                            }
                          }
                          String line =
                              "field-init: "
                                  + entities
                                  + " entities, "
                                  + missing
                                  + " without the field";
                          LOGGER.info(line);
                          command.getSource().sendSuccess(() -> Component.literal(line), false);
                          return missing;
                        })));
  }
}
