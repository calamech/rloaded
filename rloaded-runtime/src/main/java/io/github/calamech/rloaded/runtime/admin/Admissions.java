package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.admin.AddTarget;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.AuditLog;
import io.github.calamech.rloaded.admin.CompatDatabase;
import io.github.calamech.rloaded.admin.JarRules;
import io.github.calamech.rloaded.admin.LockfileLocation;
import io.github.calamech.rloaded.admin.RecordChecks;
import io.github.calamech.rloaded.admin.ReputationRules;
import io.github.calamech.rloaded.admin.Resolver;
import io.github.calamech.rloaded.admin.staging.Staging;
import io.github.calamech.rloaded.core.download.ArtifactStore;
import io.github.calamech.rloaded.core.download.RetryPolicy;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.engine.Admission;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the admission service of a dedicated server or a singleplayer world. */
public final class Admissions {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");

  private Admissions() {}

  /**
   * An admission service that reads Modrinth through {@code access}, keeps the jars it downloads in
   * {@code store}, holds versions to {@code rules} and {@code compat}, tries jars on {@code
   * staging}, and adds them to {@code target}.
   */
  public static AdmissionService service(
      ModrinthAccess access,
      Path store,
      ReputationRules rules,
      Function0<CompatDatabase> compat,
      Staging staging,
      AddTarget target) {
    Clock clock = Clock.systemUTC();
    return new AdmissionService(
        new Resolver(access.modrinth(), SharedConstants.getCurrentVersion().name()),
        new RecordChecks(access.modrinth(), rules, compat, clock),
        new ArtifactStore(
            store, access.http(), new RetryPolicy(), ArtifactStore.DEFAULT_MAX_UNSIZED_BYTES),
        new JarRules(Admission.classifier(), Admissions::gameRefusal, staging),
        target,
        clock);
  }

  /**
   * The audit log next to the lockfile at {@code lockfile}; an add's outcome it cannot take is
   * logged as an error.
   */
  public static AuditLog auditLog(LockfileLocation lockfile) {
    return new AuditLog(
        lockfile.getPath().resolveSibling("audit.log"),
        Clock.systemUTC(),
        error -> {
          LOGGER.error(
              "An add's outcome is missing from {}: {}", error.getFile(), error.getReason());
          return Unit.INSTANCE;
        });
  }

  /**
   * The compatibility database that ships in the mod jar; a broken one is a defect of the build.
   */
  public static CompatDatabase bundledCompatDatabase() {
    try (InputStream input = Admissions.class.getResourceAsStream("compat.json")) {
      if (input == null) {
        throw new IllegalStateException("compat.json is missing from the runtime mod");
      }
      return switch (CompatDatabase.Companion.parse(
          new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
        case Outcome.Ok<?> ok -> (CompatDatabase) ok.getValue();
        case Outcome.Err<?> err ->
            throw new IllegalStateException("compat.json cannot be read: " + err.getError());
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The rules the game applies to the mods it loads itself, without loading them.
  private static String gameRefusal(List<? extends Path> jars) {
    try {
      HotAdd.check(List.copyOf(jars));
      return null;
    } catch (HotAddRefusal refusal) {
      return refusal.getMessage();
    }
  }
}
