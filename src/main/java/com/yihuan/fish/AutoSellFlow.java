package com.yihuan.fish;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.yihuan.fish.input.BotInput;
import com.yihuan.fish.vision.TemplateBank;
import com.yihuan.fish.vision.TemplateMatcher;
import com.yihuan.fish.vision.TemplateMatcher.Match;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AutoSellFlow {
  private static final Logger LOG = Logger.getLogger(AutoSellFlow.class.getName());
  private static final int MAX_RETRIES = 3;

  public enum State {
    IDLE,
    ENTER_WAREHOUSE,
    CLICK_SELL_ICON,
    CLICK_SELL_ALL,
    CLICK_CONFIRM,
    CLICK_DISMISS,
    EXIT
  }

  private final FishConfig cfg;
  private final BotInput input;
  private final TemplateBank bank;

  private State state = State.IDLE;
  private int stepDelayRemaining;
  private boolean sellAllClicked;
  private int sellAllRetryCount;

  public AutoSellFlow(FishConfig cfg, BotInput input, TemplateBank bank) {
    this.cfg = cfg;
    this.input = input;
    this.bank = bank;
  }

  public boolean isActive() {
    return state != State.IDLE;
  }

  public State state() {
    return state;
  }

  public void start() {
    state = State.ENTER_WAREHOUSE;
    stepDelayRemaining = 0;
    sellAllClicked = false;
    sellAllRetryCount = 0;
    LOG.info("【售卖】已触发, 钓鱼数=" + cfg.sellThreshold);
  }

  public void reset() {
    state = State.IDLE;
    stepDelayRemaining = 0;
    sellAllClicked = false;
    sellAllRetryCount = 0;
  }

  public void tick(BufferedImage shot, Rectangle client, HWND hwnd) {
    if (state == State.IDLE) return;

    if (stepDelayRemaining > 0) {
      stepDelayRemaining -= Math.max(1, cfg.fightTickGapMinMs);
      return;
    }

    switch (state) {
      case ENTER_WAREHOUSE -> doEnterWarehouse();
      case CLICK_SELL_ICON -> doClickSellIcon(client);
      case CLICK_SELL_ALL  -> doClickSellAll(shot, client);
      case CLICK_CONFIRM   -> doClickConfirm(client);
      case CLICK_DISMISS   -> doClickDismiss(client);
      case EXIT            -> doExit();
      default -> reset();
    }
  }

  // --- Step implementations ---

  private void doEnterWarehouse() {
    LOG.info("【售卖】按Q打开仓库");
    input.keyTap(java.awt.event.KeyEvent.VK_Q);
    advance(State.CLICK_SELL_ICON, cfg.baitShopUiTransitionMs);
  }

  private void doClickSellIcon(Rectangle client) {
    int cx = client.x + cfg.sellIconMarginLeft
        + (client.width - cfg.sellIconMarginLeft - cfg.sellIconMarginRight) / 2;
    int cy = client.y + cfg.sellIconMarginTop
        + (client.height - cfg.sellIconMarginTop - cfg.sellIconMarginBottom) / 2;
    LOG.info("【售卖】点击出售图标 (" + cx + "," + cy + ")");
    input.ensureGameForeground();
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.CLICK_SELL_ALL, cfg.baitShopUiTransitionMs);
  }

  private void doClickSellAll(BufferedImage shot, Rectangle client) {
    if (!sellAllClicked) {
      // 阶段1: 点击一键售卖
      int cx = client.x + cfg.sellAllBtnMarginLeft
          + (client.width - cfg.sellAllBtnMarginLeft - cfg.sellAllBtnMarginRight) / 2;
      int cy = client.y + cfg.sellAllBtnMarginTop
          + (client.height - cfg.sellAllBtnMarginTop - cfg.sellAllBtnMarginBottom) / 2;
      LOG.info("【售卖】点击一键售卖 (" + cx + "," + cy + ") (第" + (sellAllRetryCount + 1) + "/" + MAX_RETRIES + "次)");
      input.ensureGameForeground();
      input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
      sellAllClicked = true;
      stepDelayRemaining = cfg.baitShopUiTransitionMs;
      return;
    }

    // 阶段2: 验证出售确认弹窗是否出现
    if (isSellConfirmVisible(shot)) {
      LOG.info("【售卖】✓ 出售确认弹窗已出现");
      sellAllClicked = false;
      sellAllRetryCount = 0;
      advance(State.CLICK_CONFIRM, cfg.baitShopUiTransitionMs);
    } else {
      sellAllRetryCount++;
      if (sellAllRetryCount < MAX_RETRIES) {
        LOG.info("【售卖】确认弹窗未出现, 重试一键售卖 (第" + (sellAllRetryCount + 1) + "/" + MAX_RETRIES + "次)");
        sellAllClicked = false;
        stepDelayRemaining = 200;
      } else {
        LOG.warning("【售卖】已达最大重试次数, ESC退出仓库");
        input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
        reset();
      }
    }
  }

  /** 检查出售确认弹窗是否可见 */
  private boolean isSellConfirmVisible(BufferedImage shot) {
    int w = shot.getWidth();
    int h = shot.getHeight();
    int rx = cfg.sellConfirmDialogMarginLeft;
    int ry = cfg.sellConfirmDialogMarginTop;
    int rw = w - rx - cfg.sellConfirmDialogMarginRight;
    int rh = h - ry - cfg.sellConfirmDialogMarginBottom;
    if (rw < bank.sellConfirmDialog.getWidth() || rh < bank.sellConfirmDialog.getHeight()) return false;
    Match m = TemplateMatcher.matchBest(shot, rx, ry, rw, rh, bank.sellConfirmDialog, 2);
    return m.score() >= cfg.sellConfirmDialogThreshold;
  }

  private void doClickConfirm(Rectangle client) {
    int cx = client.x + cfg.sellConfirmBtnMarginLeft
        + (client.width - cfg.sellConfirmBtnMarginLeft - cfg.sellConfirmBtnMarginRight) / 2;
    int cy = client.y + cfg.sellConfirmBtnMarginTop
        + (client.height - cfg.sellConfirmBtnMarginTop - cfg.sellConfirmBtnMarginBottom) / 2;
    LOG.info("【售卖】点击确认按钮 (" + cx + "," + cy + ")");
    input.ensureGameForeground();
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.CLICK_DISMISS, cfg.baitShopUiTransitionMs);
  }

  private void doClickDismiss(Rectangle client) {
    LOG.info("【售卖】ESC关闭出售成功页");
    input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
    advance(State.EXIT, cfg.baitShopUiTransitionMs);
  }

  private void doExit() {
    LOG.info("【售卖】ESC退出仓库, 返回钓鱼");
    input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
    reset();
  }

  private void advance(State next, int delayMs) {
    state = next;
    stepDelayRemaining = delayMs;
  }
}
