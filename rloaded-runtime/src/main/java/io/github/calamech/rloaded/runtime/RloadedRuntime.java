package io.github.calamech.rloaded.runtime;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the runtime mod, loaded on the client and the server. */
public final class RloadedRuntime implements ModInitializer {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");

  @Override
  public void onInitialize() {
    if (AgentBridge.instrumentation().isEmpty()) {
      LOGGER.warn("Runtime started without the agent: mods cannot be added while the game runs");
      return;
    }
    LOGGER.info(
        "Runtime started with the agent; enhanced class redefinition: {}",
        AgentBridge.classRedefinition().orElseThrow());
  }
}
