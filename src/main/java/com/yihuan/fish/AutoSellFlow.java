package com.yihuan.fish;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.yihuan.fish.input.BotInput;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Sub-state machine for the auto-sell flow, triggered when the number of fish caught
 * reaches or exceeds {@link FishConfig#sellThreshold}.
 *
 * <pre>
 *   READY → fishCaughtCount >= sellThreshold → AutoSellFlow
 *     Press Q (open warehouse)
 *     → Click sell icon
 *     → Click "一键售卖" (sell all)
 *     → Click "确认" (confirm)
 *     → Click anywhere (dismiss success page)
 *     → Press ESC (return to game)
 *     → back to READY, fishCaughtCount reset
 * </pre>
 */
public final class AutoSellFlow {
  private static final Logger LOG = Logger.getLogger(AutoSellFlow.class.getName());

  public enum State {
    IDLE,
    ENTER_WAREHOUSE,  // Press Q
    CLICK_SELL_ICON,  // Click the sell icon in the warehouse UI
    CLICK_SELL_ALL,   // Click "一键售卖"
    CLICK_CONFIRM,    // Click "确认" on the sell confirm dialog
    CLICK_DISMISS,    // Click anywhere to dismiss the success page
    EXIT              // Press ESC to return to fishing
  }

  private final FishConfig cfg;
  private final BotInput input;

  private State state = State.IDLE;
  private int stepDelayRemaining;

  public AutoSellFlow(FishConfig cfg, BotInput input) {
    this.cfg = cfg;
    this.input = input;
  }

  /** Whether the sell flow is currently consuming ticks. */
  public boolean isActive() {
    return state != State.IDLE;
  }

  public State state() {
    return state;
  }

  /** Start the sell flow (called from FishBot when threshold is reached). */
  public void start() {
    state = State.ENTER_WAREHOUSE;
    stepDelayRemaining = 0;
    LOG.info("Auto-sell flow started (fish caught >= " + cfg.sellThreshold + ").");
  }

  /** Reset to IDLE. Called when the flow finishes or is aborted. */
  public void reset() {
    state = State.IDLE;
    stepDelayRemaining = 0;
  }

  /**
   * Process one tick of the sell flow.
   *
   * @param shot   current screenshot (client area)
   * @param client game client screen rect
   * @param hwnd   game window handle (for PostMessage input)
   */
  public void tick(BufferedImage shot, Rectangle client, HWND hwnd) {
    if (state == State.IDLE) {
      return;
    }

    // Count down inter-step delay
    if (stepDelayRemaining > 0) {
      stepDelayRemaining -= Math.max(1, cfg.fightTickGapMinMs);
      return;
    }

    switch (state) {
      case ENTER_WAREHOUSE -> doEnterWarehouse();
      case CLICK_SELL_ICON -> doClickSellIcon(client);
      case CLICK_SELL_ALL  -> doClickSellAll(client);
      case CLICK_CONFIRM   -> doClickConfirm(client);
      case CLICK_DISMISS   -> doClickDismiss(client);
      case EXIT            -> doExit();
      default -> reset();
    }
  }

  // --- Step implementations ---

  private void doEnterWarehouse() {
    LOG.info("Pressing Q to open warehouse.");
    input.keyTap(java.awt.event.KeyEvent.VK_Q);
    advance(State.CLICK_SELL_ICON, cfg.baitShopUiTransitionMs);
  }

  private void doClickSellIcon(Rectangle client) {
    int cx = client.x + cfg.sellIconMarginLeft
        + (client.width - cfg.sellIconMarginLeft - cfg.sellIconMarginRight) / 2;
    int cy = client.y + cfg.sellIconMarginTop
        + (client.height - cfg.sellIconMarginTop - cfg.sellIconMarginBottom) / 2;
    LOG.info("Clicking sell icon at (" + cx + "," + cy + ").");
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.CLICK_SELL_ALL, cfg.baitShopUiTransitionMs);
  }

  private void doClickSellAll(Rectangle client) {
    int cx = client.x + cfg.sellAllBtnMarginLeft
        + (client.width - cfg.sellAllBtnMarginLeft - cfg.sellAllBtnMarginRight) / 2;
    int cy = client.y + cfg.sellAllBtnMarginTop
        + (client.height - cfg.sellAllBtnMarginTop - cfg.sellAllBtnMarginBottom) / 2;
    LOG.info("Clicking '一键售卖' at (" + cx + "," + cy + ").");
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.CLICK_CONFIRM, cfg.baitShopUiTransitionMs);
  }

  private void doClickConfirm(Rectangle client) {
    int cx = client.x + cfg.sellConfirmBtnMarginLeft
        + (client.width - cfg.sellConfirmBtnMarginLeft - cfg.sellConfirmBtnMarginRight) / 2;
    int cy = client.y + cfg.sellConfirmBtnMarginTop
        + (client.height - cfg.sellConfirmBtnMarginTop - cfg.sellConfirmBtnMarginBottom) / 2;
    LOG.info("Clicking sell confirm button at (" + cx + "," + cy + ").");
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.CLICK_DISMISS, cfg.baitShopUiTransitionMs);
  }

  private void doClickDismiss(Rectangle client) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int x1 = client.x + 10;
    int x2 = client.x + client.width - 10;
    int y1 = client.y + (int) (client.height * (1.0 - cfg.baitShopDismissBottomAreaPct));
    int y2 = client.y + (int) (client.height * 0.96);
    int dx = r.nextInt(x1, x2 + 1);
    int dy = r.nextInt(y1, y2 + 1);
    LOG.info("Clicking dismiss area at (" + dx + "," + dy + ") after sell success.");
    input.leftClickHoldAt(dx, dy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.EXIT, cfg.baitShopUiTransitionMs);
  }

  private void doExit() {
    LOG.info("Pressing ESC to exit warehouse and return to fishing.");
    input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
    reset();
  }

  private void advance(State next, int delayMs) {
    state = next;
    stepDelayRemaining = delayMs;
  }
}
