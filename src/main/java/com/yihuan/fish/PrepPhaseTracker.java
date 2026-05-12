package com.yihuan.fish;

/**
 * Distinguishes {@link GamePhase#READY} (扔钩前) from {@link GamePhase#WAITING_BITE}（等咬钩）when corner HUD
 * templates are not used: both phases only need F for now, but we keep two enum values for later extension
 * (e.g. different keys or timings).
 *
 * <p>Classification is driven by sequence: after a success cycle we expect a new cast ({@link #onNewCastCycle});
 * after the first cast F from READY we expect bite wait ({@link #onHookInWaterAfterCast}); after fight ends
 * without an immediate success template we assume line still in water ({@link #onFightExitedToPrep}).
 */
public final class PrepPhaseTracker {
  private boolean waitingForBite;

  public GamePhase prepPhase() {
    return waitingForBite ? GamePhase.WAITING_BITE : GamePhase.READY;
  }

  /** Success UI gone — start a new cast cycle (next action is “throw hook” / READY semantics). */
  public void onNewCastCycle() {
    waitingForBite = false;
  }

  /** Called after READY branch performs a cast tap (hook in water, switch to bite-wait semantics). */
  public void onHookInWaterAfterCast() {
    waitingForBite = true;
  }

  /** Fight UI disappeared without matching success template (e.g. lost fish) — stay in bite-wait semantics. */
  public void onFightExitedToPrep() {
    waitingForBite = true;
  }

  /** For logs: {@code false} = READY segment, {@code true} = hook in water / WAITING_BITE segment. */
  public boolean isHookInWaterSegment() {
    return waitingForBite;
  }
}
