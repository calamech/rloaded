package io.github.calamech.rloaded.runtime.staging;

import io.github.calamech.rloaded.admin.staging.StagingReport;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Records, while open, the game log's error lines and the lines that carry an exception, from every
 * logger but one, as many as a staging report keeps.
 */
final class ErrorRecorder extends AbstractAppender {
  private final Logger root = (Logger) LogManager.getRootLogger();
  private final String ignored;
  private final List<String> lines = new ArrayList<>();

  /** A recorder that leaves out the lines of the logger named {@code ignored}. */
  ErrorRecorder(String ignored) {
    super("rloaded-staging-errors", null, null, true, Property.EMPTY_ARRAY);
    this.ignored = ignored;
  }

  /** Starts recording. */
  void open() {
    start();
    root.addAppender(this);
  }

  @Override
  public void append(LogEvent event) {
    boolean failure = event.getLevel().isMoreSpecificThan(Level.ERROR) || event.getThrown() != null;
    if (!failure || ignored.equals(event.getLoggerName())) {
      return;
    }
    String line =
        "["
            + event.getLoggerName()
            + "] "
            + event.getMessage().getFormattedMessage()
            + (event.getThrown() == null ? "" : ": " + event.getThrown());
    synchronized (lines) {
      if (lines.size() < StagingReport.MAX_FAILURES) {
        lines.add(line);
      }
    }
  }

  /** Stops recording and returns what was recorded. */
  List<String> close() {
    root.removeAppender(this);
    stop();
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }
}
