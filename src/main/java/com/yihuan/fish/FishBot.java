package com.yihuan.fish;

import com.yihuan.fish.input.BotInput;
import com.yihuan.fish.vision.TemplateBank;
import com.yihuan.fish.vision.TemplateMatcher;
import com.yihuan.fish.vision.TemplateMatcher.Match;
import com.yihuan.fish.win.GameWindow;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

public final class FishBot implements Runnable {
  private static final Logger LOG = Logger.getLogger(FishBot.class.getName());

  private final FishConfig cfg;
  private final GameWindow window;
  private final BotInput input;
  private final PhaseDetector detector;
  private final PhaseStabilizer stabilizer;
  private final BaitShopFlow baitShop;
  private final AutoSellFlow autoSell;
  private final TemplateBank bank;

  private volatile boolean stopRequested;

  private long fightEnteredMonoMs = System.currentTimeMillis();
  private long lastSuccessClickMs;
  private int steerKeyHeld; // 0 = none, VK_A or VK_D while steering
  private GamePhase prevStable = GamePhase.IDLE;
  private long lastInfoHeartbeatMs;
  private long lastActionStallWarnMs;
  private boolean debugScreenshotSaved;
  private long lastMonthlyCardCheckMs;
  private int fishCaughtCount;
  private int totalFishCaught;
  private boolean autoSellWasActive;

  public FishBot(FishConfig cfg, GameWindow window, BotInput input, PhaseDetector detector,
                 PhaseStabilizer stabilizer, BaitShopFlow baitShop, AutoSellFlow autoSell,
                 TemplateBank bank) {
    this.cfg = cfg;
    this.window = window;
    this.input = input;
    this.detector = detector;
    this.stabilizer = stabilizer;
    this.baitShop = baitShop;
    this.autoSell = autoSell;
    this.bank = bank;
  }

  public void requestStop() {
    if (stopRequested) {
      return;
    }
    stopRequested = true;
    LOG.info("Stop requested (F12, Ctrl+C, or JVM shutdown).");
  }

  public boolean isStopRequested() {
    return stopRequested;
  }

  public int getFishCaughtCount() { return fishCaughtCount; }
  public int getTotalFishCaught() { return totalFishCaught; }

  @Override
  public void run() {
    LOG.info("Fish bot started. Focus game window; F12 stops.");
    try {
      while (!stopRequested) {
        try {
          tick();
        } catch (Exception ex) {
          String stateInfo = "stable=" + prevStable
              + " baitShop=" + (baitShop != null ? baitShop.state() : "null")
              + " autoSell=" + (autoSell != null ? autoSell.state() : "null");
          LOG.log(Level.SEVERE, "Tick error (" + stateInfo + ")", ex);
          input.humanGapMs(400, 900);
        }
      }
    } finally {
      LOG.info("Fish bot stopped.");
    }
  }

  private void tick() throws Exception {
    try {
      if (!window.refresh() || !window.isValid()) {
        LOG.warning("Game window not found (title contains '" + cfg.windowTitleSubstring + "').");
        input.humanGapMs(cfg.idlePollIntervalMs, cfg.idlePollIntervalMs + 200);
        return;
      }

      Rectangle client = window.clientScreenRect();
      if (client.width < 100 || client.height < 100) {
        input.humanGapMs(cfg.idlePollIntervalMs, cfg.idlePollIntervalMs + 200);
        return;
      }

      warnIfUnexpectedClientSize(client);

      // 月卡检测: 在时间窗口内每5秒截屏检测一次
      if (isMonthlyCardTimeWindow()) {
        long now = System.currentTimeMillis();
        if (now - lastMonthlyCardCheckMs >= cfg.monthlyCardPollIntervalMs) {
          lastMonthlyCardCheckMs = now;
          handleMonthlyCardPopup(client);
        }
      }

      BufferedImage shot;
      if (cfg.postMessageKeys && window.getHwnd() != null) {
        shot = input.captureClient(window.getHwnd());
        if (shot == null) {
          shot = input.captureScreen(client); // PrintWindow failed, fallback to BitBlt
        }
      } else {
        shot = input.captureScreen(client);
      }
      if (shot == null) {
        return;
      }
      saveDebugScreenshot(shot);
      PhaseDetector.RawDetect raw = detector.detect(shot);
      GamePhase stable = stabilizer.update(raw.phase());

      if (stable != prevStable) {
        LOG.info("Phase: " + prevStable + " -> " + stable);
      }

      onPhaseEdge(stable);
      handleFightTimerAndStall(stable);

      // READY covers both "ready to cast" and "hook in water" — no visual templates exist for
      // these, so PhaseDetector returns READY when SUCCESS/FIGHTING don't match. Continuous F spam
      // is safe: pressing F during the cast animation has no side effect in the game, and it
      // ensures we enter FIGHTING on the very first tick where the fish bites.
      switch (stable) {
        case READY -> {
          if (raw.phase() == GamePhase.FIGHTING && raw.fighting() != null) {
            baitShop.reset();
            autoSell.reset();
            doFight(raw);
          } else if (baitShop.isActive()) {
            releaseSteerKey();
            baitShop.tick(shot, client, window.getHwnd());
          } else if (autoSell.isActive()) {
            releaseSteerKey();
            autoSell.tick(shot, client, window.getHwnd());
          } else {
            releaseSteerKey();
            if (cfg.needsSellBeforeBait) {
              LOG.info("需要先售卖再购买鱼饵");
              cfg.needsSellBeforeBait = false;
              autoSell.start();
            } else if (fishCaughtCount >= cfg.sellThreshold && cfg.sellThreshold > 0) {
              autoSell.start();
            } else {
              // Pre-READY 检测: 误触ESC来到钓鱼前页面时, 点"开始钓鱼"
              if (checkPreReady(client)) {
                return;
              }
              prepSpamF();
              baitShop.onCastAttempt();
            }
          }
        }
        case FIGHTING -> doFight(raw);
        case SUCCESS -> {
          releaseSteerKey();
          doSuccess(client);
        }
        case IDLE -> {
          releaseSteerKey();
          input.humanGapMs(cfg.idlePollIntervalMs, cfg.idlePollIntervalMs + 220);
        }
        default -> {
          releaseSteerKey();
          input.humanGapMs(cfg.idlePollIntervalMs, cfg.idlePollIntervalMs + 220);
        }
      }

      long hb = System.currentTimeMillis();
      if (hb - lastInfoHeartbeatMs >= cfg.infoHeartbeatIntervalMs) {
        if (stable == GamePhase.IDLE
            && raw.phase() != GamePhase.IDLE
            && hb - lastActionStallWarnMs >= 8000) {
          LOG.warning(
              "Stall: stable=IDLE raw="
                  + raw.phase()
                  + " pending="
                  + stabilizer.pendingPhase()
                  + " streak="
                  + stabilizer.pendingStreak()
                  + "/"
                  + cfg.phaseStableFrames);
          lastActionStallWarnMs = hb;
        }
        lastInfoHeartbeatMs = hb;
        LOG.info("Heartbeat — stable=" + stable + " raw=" + raw.phase() + " cycle=" + fishCaughtCount + " total=" + totalFishCaught);
      }

      // Auto-sell just went from active → IDLE: selling done, reset the counter.
      if (autoSellWasActive && !autoSell.isActive()) {
        LOG.info("Sell flow complete, resetting fish count.");
        fishCaughtCount = 0;
      }
      autoSellWasActive = autoSell.isActive();

      prevStable = stable;
      shot.flush();
    } finally {
      if (Thread.interrupted()) {
        stopRequested = true;
      }
    }
  }

  private void warnIfUnexpectedClientSize(Rectangle client) {
    if (client.width != cfg.expectedClientWidth || client.height != cfg.expectedClientHeight) {
      // once per many ticks would spam — rely on single log at debug
      LOG.fine(
          "Client size "
              + client.width
              + "x"
              + client.height
              + " differs from expected "
              + cfg.expectedClientWidth
              + "x"
              + cfg.expectedClientHeight
              + "; ROI fractions still apply.");
    }
  }

  private void onPhaseEdge(GamePhase stable) {
    if (stable == GamePhase.FIGHTING && prevStable != GamePhase.FIGHTING) {
      fightEnteredMonoMs = System.currentTimeMillis();
    }
    if (stable == GamePhase.SUCCESS && prevStable != GamePhase.SUCCESS) {
      fishCaughtCount++;
      totalFishCaught++;
      LOG.info("Fish caught! Total: " + totalFishCaught + " (cycle: " + fishCaughtCount + ")");
      if (cfg.maxFishCount > 0 && totalFishCaught >= cfg.maxFishCount) {
        LOG.info("Reached max fish count (" + cfg.maxFishCount + "), stopping.");
        requestStop();
      }
    }
  }

  private void handleFightTimerAndStall(GamePhase stable) {
    if (stable != GamePhase.FIGHTING) {
      return;
    }
    long now = System.currentTimeMillis();
    if (now - fightEnteredMonoMs > cfg.fightingStallResetMs) {
      LOG.warning("Fighting stall timeout; sending reset key and clearing stabilizer.");
      input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
      stabilizer.reset();
      fightEnteredMonoMs = now;
      input.humanGapMs(500, 900);
    }
  }

  /**
   * Sends one F key press then waits 75–99ms (randomised to avoid anti-bot detection). No
   * PrepPhaseTracker state machine: READY and WAITING_BITE were merged because both just need
   * continuous F spam. The old approach (READY → press F → WAITING_BITE → press F) introduced a
   * 2–15s delay from the cast-repeat cooldown and the NCC template matching bottleneck.
   */
  /** Saves the first captured frame to {@code image/screenshot_debug.png} with the fightIndicatorRoi
   *  outlined in red so you can visually verify the ROI position. Only runs once per session. */
  private void saveDebugScreenshot(BufferedImage shot) {
    if (debugScreenshotSaved) {
      return;
    }
    debugScreenshotSaved = true;
    try {
      int w = shot.getWidth();
      int h = shot.getHeight();
      BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2 = copy.createGraphics();
      g2.drawImage(shot, 0, 0, null);
      // Draw fightIndicatorRoi as a red rectangle
      int rx = (int) Math.round(cfg.fightIndicatorRoiL * w);
      int ry = (int) Math.round(cfg.fightIndicatorRoiT * h);
      int rw = (int) Math.round((cfg.fightIndicatorRoiR - cfg.fightIndicatorRoiL) * w);
      int rh = (int) Math.round((cfg.fightIndicatorRoiB - cfg.fightIndicatorRoiT) * h);
      g2.setColor(Color.RED);
      g2.drawRect(rx, ry, rw, rh);
      // Draw fightRoi as a green rectangle (slider band)
      int fx = (int) Math.round(cfg.fightRoiL * w);
      int fy = (int) Math.round(cfg.fightRoiT * h);
      int fw = (int) Math.round((cfg.fightRoiR - cfg.fightRoiL) * w);
      int fh = (int) Math.round((cfg.fightRoiB - cfg.fightRoiT) * h);
      g2.setColor(Color.GREEN);
      g2.drawRect(fx, fy, fw, fh);
      // Draw successRoi as a blue rectangle
      int sx = (int) Math.round(cfg.successRoiL * w);
      int sy = (int) Math.round(cfg.successRoiT * h);
      int sw = (int) Math.round((cfg.successRoiR - cfg.successRoiL) * w);
      int sh = (int) Math.round((cfg.successRoiB - cfg.successRoiT) * h);
      g2.setColor(Color.BLUE);
      g2.drawRect(sx, sy, sw, sh);
      g2.dispose();
      File out = cfg.imageDir.resolve("screenshot_debug.png").toFile();
      ImageIO.write(copy, "PNG", out);
      LOG.fine(
          "Debug screenshot: " + out.getAbsolutePath()
              + " fi=(" + rx + "," + ry + " " + rw + "x" + rh + ")"
              + " fight=(" + fx + "," + fy + " " + fw + "x" + fh + ")"
              + " succ=(" + sx + "," + sy + " " + sw + "x" + sh + ")");
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to save debug screenshot", e);
    }
  }

  private void prepSpamF() {
    input.keyTap(cfg.biteVk);
    input.humanGapMs(cfg.prepSpamFMinMs, cfg.prepSpamFMaxMs);
  }

  private void doFight(PhaseDetector.RawDetect raw) {
    if (raw.phase() != GamePhase.FIGHTING || raw.fighting() == null) {
      releaseSteerKey();
      input.humanGapMs(cfg.fightingCaptureIntervalMs, cfg.fightingCaptureIntervalMs + 30);
      return;
    }
    PhaseDetector.FightingTargets f = raw.fighting();
    int yCx = f.yellowCx();
    int gLeft = f.greenLeft();
    int gRight = f.greenRight();

    if (ThreadLocalRandom.current().nextInt(100) == 0) {
      releaseSteerKey();
      input.humanGapMs(20, 60);
      return;
    }

    int dz = cfg.steerDeadzonePx;
    if (yCx >= gLeft - dz && yCx <= gRight + dz) {
      // Inside green zone — release.
      releaseSteerKey();
    } else if (yCx < gLeft) {
      // Yellow left of green — hold RIGHT (D).
      holdSteerKey(cfg.steerRightVk);
    } else {
      // Yellow right of green — hold LEFT (A).
      holdSteerKey(cfg.steerLeftVk);
    }
    // Minimal gap between checks — key stays held, giving smooth continuous movement.
    input.humanGapMs(cfg.fightTickGapMinMs, cfg.fightTickGapMaxMs);
  }

  private void holdSteerKey(int vk) {
    if (steerKeyHeld == vk) {
      return;
    }
    if (steerKeyHeld != 0) {
      input.keyUp(steerKeyHeld);
    }
    input.keyDown(vk);
    steerKeyHeld = vk;
  }

  private void releaseSteerKey() {
    if (steerKeyHeld != 0) {
      input.keyUp(steerKeyHeld);
      steerKeyHeld = 0;
    }
  }

  private long lastTimeWindowCacheMs;
  private boolean cachedTimeWindowResult;

  /** 判断当前时间是否在月卡检测窗口(UTC+8 4:59~5:01)内. 结果缓存1秒避免每次tick都创建Calendar. */
  private boolean isMonthlyCardTimeWindow() {
    long now = System.currentTimeMillis();
    if (now - lastTimeWindowCacheMs < 1000) {
      return cachedTimeWindowResult;
    }
    lastTimeWindowCacheMs = now;
    java.util.Calendar c = java.util.Calendar.getInstance();
    c.add(java.util.Calendar.HOUR_OF_DAY, 8);
    int h = c.get(java.util.Calendar.HOUR_OF_DAY);
    int m = c.get(java.util.Calendar.MINUTE);
    int start = cfg.monthlyCardCheckHourStart * 60 + cfg.monthlyCardCheckMinuteStart;
    int end = cfg.monthlyCardCheckHourEnd * 60 + cfg.monthlyCardCheckMinuteEnd;
    int minutes = h * 60 + m;
    cachedTimeWindowResult = minutes >= start && minutes <= end;
    return cachedTimeWindowResult;
  }

  /** 检测 Pre-READY 界面, 如果有则点击"开始钓鱼"并验证是否进入 READY */
  private boolean checkPreReady(Rectangle client) {
    BufferedImage shot = input.captureScreen(client);
    if (shot == null) return false;
    try {
      // --- 先检测"开始钓鱼"按钮 ---
      int w = shot.getWidth();
      int h = shot.getHeight();
      int rx = cfg.preReadyBtnMarginLeft;
      int ry = cfg.preReadyBtnMarginTop;
      int rw = w - rx - cfg.preReadyBtnMarginRight;
      int rh = h - ry - cfg.preReadyBtnMarginBottom;
      if (rw < bank.preReadyStartBtn.getWidth() || rh < bank.preReadyStartBtn.getHeight()) return false;
      Match m = TemplateMatcher.matchBest(shot, rx, ry, rw, rh, bank.preReadyStartBtn, 2);
      if (m.score() < cfg.preReadyBtnThreshold) return false;

      int cx = client.x + m.x() + bank.preReadyStartBtn.getWidth() / 2;
      int cy = client.y + m.y() + bank.preReadyStartBtn.getHeight() / 2;
      LOG.info("检测到 Pre-READY 界面, 点击开始钓鱼 (" + cx + "," + cy + ") score="
          + String.format("%.2f", m.score()));
      input.leftClickHoldAt(cx, cy, 0, 300);

      // --- 检测鱼饵未选择弹窗 ---
      for (int i = 0; i < 5; i++) {
        input.humanGapMs(100, 150);
        BufferedImage check = input.captureScreen(client);
        if (check == null) continue;
        try {
          int cw = check.getWidth();
          int ch = check.getHeight();
          int brx = cfg.baitNotSelectedMarginLeft;
          int bry = cfg.baitNotSelectedMarginTop;
          int brw = cw - brx - cfg.baitNotSelectedMarginRight;
          int brh = ch - bry - cfg.baitNotSelectedMarginBottom;
          if (brw >= bank.baitNotSelected.getWidth() && brh >= bank.baitNotSelected.getHeight()) {
            Match bm = TemplateMatcher.matchBest(check, brx, bry, brw, brh, bank.baitNotSelected, 2);
            if (bm.score() >= cfg.baitNotSelectedThreshold) {
              int bCx = client.x + bm.x() + bank.baitNotSelected.getWidth() / 2;
              int bCy = client.y + bm.y() + bank.baitNotSelected.getHeight() / 2;
              LOG.info("检测到鱼饵未选择弹窗, 点击打开轮盘选择鱼饵");
              input.leftClickHoldAt(bCx, bCy, 0, 200);
              baitShop.startWheelSelection();
              return true; // 启动轮盘, 后续 tick 会处理 baitShop
            }
          }
          // 检查开始钓鱼按钮是否消失（正常进入 READY）
          int prw = cw - cfg.preReadyBtnMarginLeft - cfg.preReadyBtnMarginRight;
          int prh = ch - cfg.preReadyBtnMarginTop - cfg.preReadyBtnMarginBottom;
          if (prw >= bank.preReadyStartBtn.getWidth() && prh >= bank.preReadyStartBtn.getHeight()) {
            Match m2 = TemplateMatcher.matchBest(check, cfg.preReadyBtnMarginLeft, cfg.preReadyBtnMarginTop,
                prw, prh, bank.preReadyStartBtn, 2);
            if (m2.score() < cfg.preReadyBtnThreshold) {
              LOG.info("✓ 已进入 READY 页面");
              return false;
            }
          }
        } finally {
          check.flush();
        }
      }
      LOG.warning("点击开始钓鱼后界面仍在, 再点一次");
      return true;
    } finally {
      shot.flush();
    }
  }

  /**
   * 月卡领流程: 在 4:59~5:01 (UTC+8) 窗口内每5秒调用一次.
   *
   * Step 1 — 检测 /image/月卡领取.png → 按ESC关闭领取页, 进入确认页.
   * Step 2 — 检测 /image/月卡领取确认.png → 点击下方20%区域退出,
   *          最多重试 {@link FishConfig#monthlyCardConfirmMaxRetries} 次,
   *          仍不退出则强制ESC.
   */
  private void handleMonthlyCardPopup(Rectangle client) {
    BufferedImage shot = input.captureScreen(client);
    if (shot == null) return;
    try {
      int w = shot.getWidth();
      int h = shot.getHeight();

      int cRx = cfg.monthlyCardClaimMarginLeft;
      int cRy = cfg.monthlyCardClaimMarginTop;
      int cRw = w - cRx - cfg.monthlyCardClaimMarginRight;
      int cRh = h - cRy - cfg.monthlyCardClaimMarginBottom;
      if (cRw >= bank.monthlyCardClaim.getWidth() && cRh >= bank.monthlyCardClaim.getHeight()) {
        Match m = TemplateMatcher.matchBest(shot, cRx, cRy, cRw, cRh, bank.monthlyCardClaim, 2);
        if (m.score() >= cfg.monthlyCardClaimThreshold) {
          LOG.info("检测到月卡领取页面(score=" + String.format("%.2f", m.score()) + "), 按ESC");
          input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
          input.humanGapMs(300, 500);
          return;
        }
      }

      int confRx = cfg.monthlyCardClaimConfirmMarginLeft;
      int confRy = cfg.monthlyCardClaimConfirmMarginTop;
      int confRw = w - confRx - cfg.monthlyCardClaimConfirmMarginRight;
      int confRh = h - confRy - cfg.monthlyCardClaimConfirmMarginBottom;
      if (confRw >= bank.monthlyCardClaimConfirm.getWidth()
          && confRh >= bank.monthlyCardClaimConfirm.getHeight()) {
        Match m = TemplateMatcher.matchBest(
            shot, confRx, confRy, confRw, confRh, bank.monthlyCardClaimConfirm, 2);
        if (m.score() >= cfg.monthlyCardClaimConfirmThreshold) {
          LOG.info("检测到月卡领取确认页面(score="
              + String.format("%.2f", m.score()) + "), 点击下方退出");
          for (int i = 0; i < cfg.monthlyCardConfirmMaxRetries; i++) {
            int cx = client.x + client.width / 2;
            int cy = client.y
                + (int) (client.height * (1.0 - cfg.monthlyCardDismissBottomAreaPct / 2));
            input.leftClickHoldAt(cx, cy, 0, 200);
            input.humanGapMs(200, 400);

            BufferedImage recheck = input.captureScreen(client);
            if (recheck == null) continue;
            try {
              int rw = recheck.getWidth();
              int rh = recheck.getHeight();
              int rrw =
                  rw - cfg.monthlyCardClaimConfirmMarginLeft - cfg.monthlyCardClaimConfirmMarginRight;
              int rrh =
                  rh - cfg.monthlyCardClaimConfirmMarginTop - cfg.monthlyCardClaimConfirmMarginBottom;
              if (rrw >= bank.monthlyCardClaimConfirm.getWidth()
                  && rrh >= bank.monthlyCardClaimConfirm.getHeight()) {
                Match m2 = TemplateMatcher.matchBest(
                    recheck,
                    cfg.monthlyCardClaimConfirmMarginLeft,
                    cfg.monthlyCardClaimConfirmMarginTop,
                    rrw,
                    rrh,
                    bank.monthlyCardClaimConfirm,
                    2);
                if (m2.score() < cfg.monthlyCardClaimConfirmThreshold) {
                  LOG.info("✓ 月卡领取确认页面已关闭");
                  return;
                }
              } else {
                return;
              }
            } finally {
              recheck.flush();
            }
            LOG.info("月卡领取确认页面仍在, 第" + (i + 1) + "/"
                + cfg.monthlyCardConfirmMaxRetries + "次重试");
          }
          LOG.warning("月卡领取确认页面重试" + cfg.monthlyCardConfirmMaxRetries
              + "次未关闭, 强制ESC退出");
          input.keyTap(java.awt.event.KeyEvent.VK_ESCAPE);
        }
      }
    } finally {
      shot.flush();
    }
  }

  private void doSuccess(Rectangle client) {
    long now = System.currentTimeMillis();
    if (now - lastSuccessClickMs > 900) {
      input.clickClient(client, cfg.clickJitterPx);
      lastSuccessClickMs = now;
    }
    input.humanGapMs(cfg.postActionRestMinMs, cfg.postActionRestMaxMs);
  }
}
