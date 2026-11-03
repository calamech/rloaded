package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.runtime.mixin.ArrayBackedEventAccess;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The Fabric API events that already fired in the game an add loads mods into, in the order they
 * fired, each with how to call one listener the way the event called its own. A mod that sets up
 * its state in one of them, such as a minimap that creates its server data when the server started,
 * would otherwise never get it.
 */
public final class StartedEvents {
  /** No event fired yet, as before a world exists: the game calls the mods' listeners itself. */
  public static final StartedEvents NONE = new StartedEvents(List.of());

  private final List<Replay<?>> replays;

  private StartedEvents(List<Replay<?>> replays) {
    this.replays = replays;
  }

  /**
   * The events of a server that is running: it was starting, loaded its levels, then started.
   *
   * @param server the running server
   * @return its started events
   */
  public static StartedEvents server(MinecraftServer server) {
    return new StartedEvents(
        List.of(
            new Replay<>(
                "the server was starting",
                ServerLifecycleEvents.SERVER_STARTING,
                listener -> listener.onServerStarting(server)),
            new Replay<>(
                "the server loaded its levels",
                ServerLevelEvents.LOAD,
                listener -> {
                  for (ServerLevel level : server.getAllLevels()) {
                    listener.onLevelLoad(server, level);
                  }
                }),
            new Replay<>(
                "the server started",
                ServerLifecycleEvents.SERVER_STARTED,
                listener -> listener.onServerStarted(server))));
  }

  /**
   * Builds the started events of one event.
   *
   * @param what what the event told, for a failure message
   * @param event the event
   * @param call how the event calls one listener
   * @param <T> the listener type
   * @return started events with this one
   */
  public static <T> StartedEvents of(String what, Event<T> event, Consumer<T> call) {
    return new StartedEvents(List.of(new Replay<>(what, event, call)));
  }

  /**
   * These events, then {@code later}'s.
   *
   * @param later events that fired after these
   * @return both
   */
  public StartedEvents then(StartedEvents later) {
    List<Replay<?>> both = new ArrayList<>(replays);
    both.addAll(later.replays);
    return new StartedEvents(List.copyOf(both));
  }

  /** The listeners of each event right now, by identity. */
  Snapshot snapshot() {
    List<Set<Object>> listeners = new ArrayList<>();
    for (Replay<?> replay : replays) {
      Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
      Collections.addAll(current, replay.listeners());
      listeners.add(current);
    }
    return new Snapshot(listeners);
  }

  /** The listeners registered since {@code before}, as calls to make, in the events' order. */
  List<Call> registeredSince(Snapshot before) {
    List<Call> calls = new ArrayList<>();
    for (int i = 0; i < replays.size(); i++) {
      Replay<?> replay = replays.get(i);
      for (Object listener : replay.listeners()) {
        if (!before.listeners().get(i).contains(listener)) {
          calls.add(new Call(i, replay.what(), () -> replay.callUnchecked(listener)));
        }
      }
    }
    return calls;
  }

  /** The listeners of each event at one point. */
  record Snapshot(List<Set<Object>> listeners) {}

  /** One listener to call: the index of its event, what the event told, and the call. */
  record Call(int event, String what, Runnable call) {}

  private record Replay<T>(String what, Event<T> event, Consumer<T> call) {
    Object[] listeners() {
      return ((ArrayBackedEventAccess) event).rloaded$handlers();
    }

    @SuppressWarnings("unchecked")
    void callUnchecked(Object listener) {
      call.accept((T) listener);
    }
  }
}
