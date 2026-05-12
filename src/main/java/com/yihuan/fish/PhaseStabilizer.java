package com.yihuan.fish;

public final class PhaseStabilizer {
  private final int need;
  private GamePhase pending = GamePhase.UNKNOWN;
  private int streak;
  private GamePhase confirmed = GamePhase.IDLE;

  public PhaseStabilizer(int stableFrames) {
    this.need = Math.max(1, stableFrames);
  }

  /** Returns stabilized phase used for actions; updates internal confirmation. */
  public GamePhase update(GamePhase raw) {
    if (raw == pending) {
      streak++;
    } else {
      pending = raw;
      streak = 1;
    }
    if (streak >= need) {
      confirmed = pending;
    }
    return confirmed;
  }

  public GamePhase confirmed() {
    return confirmed;
  }

  /** For diagnostics: current streak length for {@link #pending}. */
  public int pendingStreak() {
    return streak;
  }

  public GamePhase pendingPhase() {
    return pending;
  }

  public void reset() {
    pending = GamePhase.UNKNOWN;
    streak = 0;
    confirmed = GamePhase.IDLE;
  }
}
