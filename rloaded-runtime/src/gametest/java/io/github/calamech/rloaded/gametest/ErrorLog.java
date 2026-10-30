package io.github.calamech.rloaded.gametest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** Records the error-level lines that reach the game log while it is open. */
final class ErrorLog extends AbstractAppender implements AutoCloseable {
  private final Logger root = (Logger) LogManager.getRootLogger();
  private final List<String> lines = new CopyOnWriteArrayList<>();

  private ErrorLog() {
    super("rloaded-gametest-errors", null, null, true, Property.EMPTY_ARRAY);
  }

  /** Starts recording error-level lines from every logger. */
  static ErrorLog open() {
    ErrorLog log = new ErrorLog();
    log.start();
    log.root.addAppender(log);
    return log;
  }

  @Override
  public void append(LogEvent event) {
    if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
      lines.add("[" + event.getLoggerName() + "] " + event.getMessage().getFormattedMessage());
    }
  }

  /** The error-level lines recorded so far. */
  List<String> lines() {
    return List.copyOf(lines);
  }

  @Override
  public void close() {
    root.removeAppender(this);
    stop();
  }
}
