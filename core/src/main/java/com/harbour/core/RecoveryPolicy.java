package com.harbour.core;

/** Bounded backoff shared by Android player and deterministic unit tests. */
public final class RecoveryPolicy {
  public enum Fault {
    TRANSIENT,
    ACCESS_DENIED,
    RATE_LIMITED,
    DRM,
    UNSUPPORTED,
    SECURITY,
    NOT_FOUND,
    LIVE_WINDOW
  }

  public enum Action {
    RETRY,
    SEEK_LIVE,
    NEXT_SOURCE,
    STOP
  }

  public static final class Decision {
    public final Action action;
    public final long delayMs;

    Decision(Action action, long delayMs) {
      this.action = action;
      this.delayMs = delayMs;
    }
  }

  private int failures;

  public void reset() {
    failures = 0;
  }

  public Decision onFailure(Fault fault, boolean alternativeAvailable) {
    if (fault == Fault.LIVE_WINDOW) return new Decision(++failures <= 3 ? Action.SEEK_LIVE : Action.STOP, 0);
    if (fault == Fault.RATE_LIMITED || fault == Fault.ACCESS_DENIED || fault == Fault.DRM || fault == Fault.SECURITY) return new Decision(Action.STOP, 0);
    if (fault == Fault.UNSUPPORTED || fault == Fault.NOT_FOUND)
      return new Decision(alternativeAvailable ? Action.NEXT_SOURCE : Action.STOP, 0);
    if (alternativeAvailable && failures >= 1) return new Decision(Action.NEXT_SOURCE, 0);
    if (++failures <= 3) return new Decision(Action.RETRY, 1000L << (failures - 1));
    return new Decision(alternativeAvailable ? Action.NEXT_SOURCE : Action.STOP, 0);
  }
}
