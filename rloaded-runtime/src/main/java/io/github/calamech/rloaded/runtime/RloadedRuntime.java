package io.github.calamech.rloaded.runtime;

import io.github.calamech.rloaded.runtime.admin.AdminCommands;
import io.github.calamech.rloaded.runtime.engine.DataReload;
import io.github.calamech.rloaded.runtime.engine.Reconfigurations;
import io.github.calamech.rloaded.runtime.engine.RegistryThaw;
import io.github.calamech.rloaded.runtime.join.JoinPayloads;
import io.github.calamech.rloaded.runtime.join.JoinServer;
import io.github.calamech.rloaded.runtime.staging.StagingDryRun;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the runtime mod, loaded on the client and the server. */
public final class RloadedRuntime implements ModInitializer {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");

  @Override
  public void onInitialize() {
    RegistryThaw.rememberIntrusiveRegistries();
    JoinPayloads.register();
    Reconfigurations.register();
    // A dedicated server and the server of a singleplayer world both reload data packs after an
    // add.
    DataReload.register();
    if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
      JoinServer.register();
      AdminCommands.register();
      StagingDryRun.registerIfActive();
    }
    if (AgentBridge.instrumentation().isEmpty()) {
      LOGGER.warn("Runtime started without the agent: mods cannot be added while the game runs");
      return;
    }
    LOGGER.info(
        "Runtime started with the agent; enhanced class redefinition: {}",
        AgentBridge.classRedefinition().orElseThrow());
  }
}
