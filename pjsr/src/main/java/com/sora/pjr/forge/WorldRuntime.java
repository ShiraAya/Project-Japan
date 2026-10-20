package com.sora.pjr.forge;

import com.sora.pjr.core.WorldEngine;

public final class WorldRuntime {
  private WorldRuntime() {}

  private static volatile WorldEngine engine;
  private static Throwable initializationFailure;

  public static WorldEngine engine() {
    WorldEngine ready = engine;
    if (ready != null) return ready;
    synchronized (WorldRuntime.class) {
      if (engine != null) return engine;
      if (initializationFailure != null)
        throw new IllegalStateException("PJR world model initialization failed; original cause follows", initializationFailure);
      try {
        engine = WorldEngine.load();
        return engine;
      } catch (RuntimeException | LinkageError failure) {
        initializationFailure = failure;
        System.err.println("[PJR] World model initialization failed; preserve this first error when reporting:");
        failure.printStackTrace(System.err);
        throw new IllegalStateException("PJR world model initialization failed", failure);
      }
    }
  }
}
