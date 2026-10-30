package io.github.calamech.rloaded.gametest;

import com.sun.net.httpserver.HttpServer;
import io.github.calamech.rloaded.core.download.HttpSettings;
import io.github.calamech.rloaded.core.download.KtorHttp;
import io.github.calamech.rloaded.core.download.Origin;
import io.github.calamech.rloaded.core.download.UrlPolicy;
import io.github.calamech.rloaded.protocol.InstallStage;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.protocol.Progress;
import io.github.calamech.rloaded.protocol.ProtocolCodec;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Checks that the Kotlin libraries the runtime mod nests work in the game, with the Kotlin standard
 * library and kotlinx that Fabric Language Kotlin provides: the protocol codec, and Ktor fetching
 * from a server on the loopback interface.
 */
public final class NestedLibrariesGameTest {
  private static final long MAX_BYTES = 64;

  @GameTest
  public void nestedLibrariesWorkInTheGame(GameTestHelper helper) throws Exception {
    helper.assertTrue(
        FabricLoader.getInstance().isModLoaded("io_github_calamech_rloaded_rloaded-protocol"),
        "the loader does not list the nested protocol library");

    Progress progress = new Progress(InstallStage.DOWNLOAD, 42);
    byte[] encoded = ProtocolCodec.INSTANCE.encode(Progress.Companion.serializer(), progress);
    Outcome<Progress, ?> decoded =
        ProtocolCodec.INSTANCE.decode(Progress.Companion.serializer(), encoded);
    helper.assertValueEqual(decoded, new Outcome.Ok<>(progress), "decoded progress");

    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/hello",
        exchange -> {
          byte[] body = "hello from loopback".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    int port = server.getAddress().getPort();
    UrlPolicy policy = new UrlPolicy(Set.of(new Origin("http", "127.0.0.1", port)));
    Object fetched;
    try (KtorHttp http = new KtorHttp(policy, new HttpSettings())) {
      String url = "http://127.0.0.1:" + port + "/hello";
      fetched =
          BuildersKt.runBlocking(
              EmptyCoroutineContext.INSTANCE,
              (scope, continuation) -> http.text(url, MAX_BYTES, continuation));
    } finally {
      server.stop(0);
    }
    helper.assertValueEqual(
        fetched, new Outcome.Ok<>("hello from loopback"), "body fetched with Ktor");
    helper.succeed();
  }
}
