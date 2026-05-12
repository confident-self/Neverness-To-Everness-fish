package com.yihuan.fish;

import com.yihuan.fish.input.BotInput;
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

  private volatile boolean stopRequested;

  private long fightEnteredMonoMs = System.currentTimeMillis();
  private long lastSuccessClickMs;
  private int steerKeyHeld; // 0 = none, VK_A or VK_D while steering
  private GamePhase prevStable = GamePhase.IDLE;
  private long lastInfoHeartbeatMs;
  private long lastActionStallWarnMs;
  private boolean debugScreenshotSaved;
  private int fishCaughtCount;
  private int totalFishCaught;
  private boolean autoSellWasActive;

  public FishBot(FishConfig cfg, GameWindow window, BotInput input, PhaseDetector detector,
                 PhaseStabilizer stabilizer, BaitShopFlow baitShop, AutoSellFlow autoSell) {
    this.cfg = cfg;
    this.window = window;
    this.input = input;
    this.detector = detector;
    this.stabilizer = stabilizer;
    this.baitShop = baitShop;
    this.autoSell = autoSell;
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

  @Override
  public void run() {
    LOG.info("Fish bot started. Focus game window; F12 stops.");
    try {
      while (!stopRequested) {
        try {
          tick();
        } catch (Exception ex) {
          LOG.log(Level.WARNING, "Tick error", ex);
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
            if (fishCaughtCount >= cfg.sellThreshold && cfg.sellThreshold > 0) {
              autoSell.start();
            } else {
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

  private void doSuccess(Rectangle client) {
    long now = System.currentTimeMillis();
    if (now - lastSuccessClickMs > 900) {
      input.clickClient(client, cfg.clickJitterPx);
      lastSuccessClickMs = now;
    }
    input.humanGapMs(cfg.postActionRestMinMs, cfg.postActionRestMaxMs);
  }
}
