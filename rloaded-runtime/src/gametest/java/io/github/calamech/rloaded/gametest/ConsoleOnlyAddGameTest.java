package io.github.calamech.rloaded.gametest;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

/**
 * Checks that {@code rloaded add} is the server console's and RCON's only: an entity running it,
 * such as an operator, gets the game's unknown command error whatever its level, and so does a
 * command block.
 */
public final class ConsoleOnlyAddGameTest {
  private static final String UNKNOWN = "Unknown or incomplete command";

  @GameTest
  public void onlyTheConsoleCanAdd(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    Entity entity = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 1, 1));
    CommandSourceStack console = server.createCommandSourceStack();
    helper.assertTrue(
        server.getCommands().getDispatcher().getRoot().getChild("rloaded").canUse(console),
        "the console cannot use rloaded");

    List<String> fromEntity = new ArrayList<>();
    run(
        server,
        console
            .withPermission(LevelBasedPermissionSet.OWNER)
            .withEntity(entity)
            .withSource(capturing(fromEntity)));
    // A command block runs commands at level 2, without an entity.
    List<String> fromCommandBlock = new ArrayList<>();
    run(
        server,
        console
            .withPermission(LevelBasedPermissionSet.GAMEMASTER)
            .withSource(capturing(fromCommandBlock)));

    helper.assertTrue(
        fromEntity.stream().anyMatch(message -> message.startsWith(UNKNOWN)),
        "an entity of level 4 was not refused: " + fromEntity);
    helper.assertTrue(
        fromCommandBlock.stream().anyMatch(message -> message.startsWith(UNKNOWN)),
        "a command block was not refused: " + fromCommandBlock);
    helper.succeed();
  }

  private static void run(MinecraftServer server, CommandSourceStack source) {
    server.getCommands().performPrefixedCommand(source, "rloaded add consoleonly");
  }

  private static CommandSource capturing(List<String> messages) {
    return new CommandSource() {
      @Override
      public void sendSystemMessage(Component message) {
        messages.add(message.getString());
      }

      @Override
      public boolean acceptsSuccess() {
        return true;
      }

      @Override
      public boolean acceptsFailure() {
        return true;
      }

      @Override
      public boolean shouldInformAdmins() {
        return false;
      }
    };
  }
}
