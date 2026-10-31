package io.github.calamech.rloaded.runtime.staging;

import io.github.calamech.rloaded.admin.staging.StagingReport;
import io.github.calamech.rloaded.admin.staging.StagingServer;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.AgentBridge;
import io.github.calamech.rloaded.runtime.engine.DataReload;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The staging server's side of a dry run: once the server runs, loads the candidate jars the way an
 * add does, exercises what they bring, and writes the report the server that started it judges them
 * on. Active only in a server started with {@link StagingServer#CANDIDATES_PROPERTY}.
 *
 * <p>After the add, the run reloads the data packs, which is how the game binds the components of
 * new items and loads the new mods' data. It then ticks 200 times, places every new block, spawns
 * every new entity type, drops every new item, runs {@code /reload}, ticks 200 more times, and
 * stops the server. It fails on any exception, any error line, any line carrying an exception, a
 * failed reload, or a tick longer than 2 seconds, from the add on.
 */
public final class StagingDryRun {
  /** The logger of the dry run itself; its own lines are failures already. */
  static final String LOGGER_NAME = "Rloaded staging";

  private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);
  private static final int TICKS_BEFORE = 200;
  private static final int TICKS_AFTER = 200;
  private static final long MAX_TICK_NANOS = TimeUnit.SECONDS.toNanos(2);
  private static final int ORPHANED_EXIT = 3;

  private enum Phase {
    BINDING,
    WARMING,
    EXERCISING,
    RELOADING,
    COOLING,
    DONE
  }

  private final List<Path> candidates;
  private final Path reportFile;
  private final ErrorRecorder errors = new ErrorRecorder(LOGGER_NAME);
  private StagingReport report = StagingReport.Companion.of(List.of());
  private NewContent content = NewContent.NONE;
  private Exercise.Result exercised = Exercise.Result.NONE;
  private Phase phase = Phase.BINDING;
  private int ticks;
  private int phaseTicks;
  private long tickStart;

  private StagingDryRun(List<Path> candidates, Path reportFile) {
    this.candidates = candidates;
    this.reportFile = reportFile;
  }

  /** Whether this server is a staging server. */
  public static boolean active() {
    return System.getProperty(StagingServer.CANDIDATES_PROPERTY) != null;
  }

  /** Runs the dry run in this server if it is a staging server; the server calls it once. */
  public static void registerIfActive() {
    if (!active()) {
      return;
    }
    String reportProperty = System.getProperty(StagingServer.REPORT_PROPERTY);
    if (reportProperty == null) {
      throw new IllegalStateException(
          StagingServer.REPORT_PROPERTY + " is not set on a staging server");
    }
    List<Path> candidates =
        Arrays.stream(
                System.getProperty(StagingServer.CANDIDATES_PROPERTY).split(File.pathSeparator))
            .map(Path::of)
            .toList();
    StagingDryRun run = new StagingDryRun(candidates, Path.of(reportProperty));
    // Nobody else stops a staging server whose parent is gone.
    ProcessHandle.current().parent().ifPresent(StagingDryRun::haltWhenGone);
    ServerLifecycleEvents.SERVER_STARTED.register(run::start);
    ServerTickEvents.START_SERVER_TICK.register(server -> run.tickStart = System.nanoTime());
    ServerTickEvents.END_SERVER_TICK.register(run::tick);
  }

  private static void haltWhenGone(ProcessHandle parent) {
    CompletableFuture<?> _ =
        parent.onExit().thenRun(() -> Runtime.getRuntime().halt(ORPHANED_EXIT));
  }

  private void start(MinecraftServer server) {
    LOGGER.info("Staging server: loading {}", candidates);
    errors.open();
    Optional<Instrumentation> instrumentation = AgentBridge.instrumentation();
    if (instrumentation.isEmpty()) {
      fail("the staging server runs without the agent");
      finish(server);
      return;
    }
    Snapshot before = Snapshot.take();
    try {
      List<String> mods = HotAdd.load(candidates, instrumentation.get());
      report = StagingReport.Companion.of(mods);
      content = before.added();
      reload(server);
    } catch (HotAddRefusal refusal) {
      fail("the mods were refused: " + refusal.getMessage());
      finish(server);
    } catch (RuntimeException e) {
      LOGGER.error("Loading the mods failed", e);
      fail("loading the mods threw " + e);
      finish(server);
    }
  }

  private void tick(MinecraftServer server) {
    if (phase == Phase.DONE) {
      return;
    }
    long took = System.nanoTime() - tickStart;
    ticks++;
    phaseTicks++;
    if (took > MAX_TICK_NANOS) {
      fail("tick " + ticks + " took " + TimeUnit.NANOSECONDS.toMillis(took) + " ms");
    }
    switch (phase) {
      case WARMING -> {
        if (phaseTicks == TICKS_BEFORE) {
          next(Phase.EXERCISING);
        }
      }
      case EXERCISING -> {
        exercised = Exercise.run(server.overworld(), content);
        exercised.failures().forEach(this::fail);
        next(Phase.RELOADING);
        reload(server);
      }
      case BINDING, RELOADING -> {
        // Ends when the reload reports back.
      }
      case COOLING -> {
        if (phaseTicks == TICKS_AFTER) {
          finish(server);
        }
      }
      case DONE -> throw new IllegalStateException("the dry run already ended");
    }
  }

  private void reload(MinecraftServer server) {
    CompletableFuture<?> _ = DataReload.run(server).thenAccept(this::reloaded);
  }

  private void reloaded(boolean success) {
    if (phase != Phase.BINDING && phase != Phase.RELOADING) {
      return;
    }
    if (!success) {
      fail("/reload failed");
    }
    next(phase == Phase.BINDING ? Phase.WARMING : Phase.COOLING);
  }

  private void next(Phase following) {
    phase = following;
    phaseTicks = 0;
  }

  private void fail(String failure) {
    LOGGER.error("Staging failure: {}", failure);
    report = report.failing(failure);
  }

  private void finish(MinecraftServer server) {
    next(Phase.DONE);
    errors.close().forEach(line -> report = report.failing(line));
    report = report.exercised(exercised.blocks(), exercised.entities(), exercised.items(), ticks);
    LOGGER.info(
        "Staging server: {} blocks placed, {} entities spawned, {} items dropped, {} ticks, {}"
            + " failures",
        exercised.blocks(),
        exercised.entities(),
        exercised.items(),
        ticks,
        report.getFailures().size());
    if (report.write(reportFile) instanceof Outcome.Err<?> err) {
      LOGGER.error("Cannot write the staging report: {}", err.getError());
    }
    server.halt(false);
  }

  /** The ids in the registries content can be added to, to tell new content from old. */
  private record Snapshot(Set<Identifier> blocks, Set<Identifier> entities, Set<Identifier> items) {
    static Snapshot take() {
      return new Snapshot(
          Set.copyOf(BuiltInRegistries.BLOCK.keySet()),
          Set.copyOf(BuiltInRegistries.ENTITY_TYPE.keySet()),
          Set.copyOf(BuiltInRegistries.ITEM.keySet()));
    }

    NewContent added() {
      return new NewContent(
          added(BuiltInRegistries.BLOCK, blocks),
          added(BuiltInRegistries.ENTITY_TYPE, entities),
          added(BuiltInRegistries.ITEM, items));
    }

    private static <T> List<T> added(Registry<T> registry, Set<Identifier> before) {
      List<T> added = new ArrayList<>();
      for (Identifier id : registry.keySet()) {
        if (!before.contains(id)) {
          registry.getOptional(id).ifPresent(added::add);
        }
      }
      return added;
    }
  }
}
