package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.StartedEvents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Checks that a mod added to a running server is told, once and in order, the events that fired
 * before it came: late-lifecycle sets up its server state when the server started and needs it at
 * each player tick, like Xaero's Minimap (addTest checks the players). A mod that was there from
 * the start is not told again.
 */
public final class StartedEventsGameTest {
  private static final String MOD_PROPERTY = "rloaded.late-lifecycle-mod";
  private static final String CALLS = "rloaded.late-lifecycle.calls";

  @GameTest
  public void addedModsAreToldTheServerStarted(GameTestHelper helper) throws Exception {
    String jar = System.getProperty(MOD_PROPERTY);
    helper.assertTrue(jar != null, "run with the " + MOD_PROPERTY + " property");
    MinecraftServer server = helper.getLevel().getServer();
    int startedBefore = GameTestContent.SERVER_STARTED_CALLS.get();

    HotAdd.load(List.of(Path.of(jar)), Agent.instrumentation(), StartedEvents.server(server));

    List<String> expected = new ArrayList<>(List.of("server starting"));
    for (ServerLevel level : server.getAllLevels()) {
      expected.add("level " + level.dimension().identifier());
    }
    expected.add("server started");
    helper.assertValueEqual(
        List.of(System.getProperty(CALLS, "").split(";")), expected, "events late-lifecycle got");
    helper.assertValueEqual(
        GameTestContent.SERVER_STARTED_CALLS.get(),
        startedBefore,
        "times a mod there from the start was told the server started");
    helper.succeed();
  }
}
