package io.github.calamech.rloaded.runtime;

import io.github.calamech.rloaded.core.download.HttpSettings;
import io.github.calamech.rloaded.core.download.KtorHttp;
import io.github.calamech.rloaded.core.download.Origin;
import io.github.calamech.rloaded.core.download.UrlPolicy;
import io.github.calamech.rloaded.modrinth.Modrinth;
import io.github.calamech.rloaded.modrinth.ModrinthSettings;
import java.net.URI;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the game reaches Modrinth: Modrinth itself, or for checks only the server that {@code
 * rloaded.test.modrinth} names, which then is the only origin allowed.
 */
public record ModrinthAccess(KtorHttp http, Modrinth modrinth) {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String TEST_MODRINTH_PROPERTY = "rloaded.test.modrinth";

  /** Opens an HTTP client and a Modrinth client on it. */
  public static ModrinthAccess open() {
    String testModrinth = System.getProperty(TEST_MODRINTH_PROPERTY);
    ModrinthSettings settings = new ModrinthSettings();
    UrlPolicy policy = UrlPolicy.Companion.getPRODUCTION();
    if (testModrinth != null) {
      URI uri = URI.create(testModrinth);
      LOGGER.warn("Reading mods from the test server {} instead of Modrinth", testModrinth);
      settings =
          new ModrinthSettings(
              testModrinth + "/v2", settings.getMaxIdsPerRequest(), settings.getMaxResponseBytes());
      policy = new UrlPolicy(Set.of(new Origin(uri.getScheme(), uri.getHost(), uri.getPort())));
    }
    KtorHttp http = new KtorHttp(policy, new HttpSettings());
    return new ModrinthAccess(http, new Modrinth(http, settings));
  }
}
