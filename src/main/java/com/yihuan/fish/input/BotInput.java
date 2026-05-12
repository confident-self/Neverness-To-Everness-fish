package com.yihuan.fish.input;

import com.yihuan.fish.FishConfig;
import com.yihuan.fish.win.GameWindow;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.ThreadLocalRandom;

public final class BotInput {
  private final Robot robot;
  private final GameWindow gameWindow;
  private final FishConfig cfg;
  private final boolean winKeys;
  private long lastRefocusMs;

  public BotInput(GameWindow gameWindow, FishConfig cfg) throws AWTException {
    this.robot = new Robot();
    this.gameWindow = gameWindow;
    this.cfg = cfg;
    this.robot.setAutoDelay(0);
    this.winKeys = cfg.winSendInputKeys;
  }

  public BufferedImage captureScreen(Rectangle screenRect) {
    return WinScreenCapture.capture(screenRect);
  }

  /** Background capture via PrintWindow — works when window is covered/minimized (needs admin). */
  public BufferedImage captureClient(com.sun.jna.platform.win32.WinDef.HWND hwnd) {
    return BackgroundCapture.captureClient(hwnd);
  }

  public void keyTap(int vk) {
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      WinPostMessageKeys.keyDownUp(gameWindow.getHwnd(), vk, cfg.winSendInputUseScanCode);
      sleepMs(28 + ThreadLocalRandom.current().nextInt(18));
      return;
    }
    beforeKeyboard();
    if (winKeys) {
      WinSendInputKeys.keyDownUp(vk, cfg.winSendInputUseScanCode);
      sleepMs(28 + ThreadLocalRandom.current().nextInt(18));
    } else {
      robot.keyPress(vk);
      robot.delay(28 + ThreadLocalRandom.current().nextInt(18));
      robot.keyRelease(vk);
    }
  }

  /** Press a key and keep it held (for continuous steering). */
  public void keyDown(int vk) {
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      WinPostMessageKeys.keyDown(gameWindow.getHwnd(), vk, cfg.winSendInputUseScanCode);
      return;
    }
    beforeKeyboard();
    if (winKeys) {
      WinSendInputKeys.keyDown(vk, cfg.winSendInputUseScanCode);
    } else {
      robot.keyPress(vk);
    }
  }

  /** Release a previously held key. */
  public void keyUp(int vk) {
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      WinPostMessageKeys.keyUp(gameWindow.getHwnd(), vk, cfg.winSendInputUseScanCode);
      return;
    }
    if (winKeys) {
      WinSendInputKeys.keyUp(vk, cfg.winSendInputUseScanCode);
    } else {
      robot.keyRelease(vk);
    }
  }

  public void keyHoldMs(int vk, int holdMs) {
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      int h = Math.max(5, holdMs);
      WinPostMessageKeys.keyDown(gameWindow.getHwnd(), vk, cfg.winSendInputUseScanCode);
      sleepMs(h);
      WinPostMessageKeys.keyUp(gameWindow.getHwnd(), vk, cfg.winSendInputUseScanCode);
      return;
    }
    beforeKeyboard();
    int h = Math.max(5, holdMs);
    if (winKeys) {
      WinSendInputKeys.keyDown(vk, cfg.winSendInputUseScanCode);
      sleepMs(h);
      WinSendInputKeys.keyUp(vk, cfg.winSendInputUseScanCode);
    } else {
      robot.keyPress(vk);
      robot.delay(h);
      robot.keyRelease(vk);
    }
  }

  public void clickClient(Rectangle clientScreen, int jitterPx) {
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      ThreadLocalRandom r = ThreadLocalRandom.current();
      int jx = r.nextInt(-jitterPx, jitterPx + 1);
      int jy = r.nextInt(-jitterPx, jitterPx + 1);
      int sx = clientScreen.x + clientScreen.width / 2 + jx;
      int sy = clientScreen.y + clientScreen.height / 2 + jy;
      sx = clamp(sx, clientScreen.x + 2, clientScreen.x + clientScreen.width - 3);
      sy = clamp(sy, clientScreen.y + 2, clientScreen.y + clientScreen.height - 3);
      SemiBackgroundMouse.backgroundClick(gameWindow.getHwnd(), sx, sy);
      sleepMs(30 + r.nextInt(40));
      return;
    }
    beforePointer();
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int jx = r.nextInt(-jitterPx, jitterPx + 1);
    int jy = r.nextInt(-jitterPx, jitterPx + 1);
    int cx = clientScreen.x + clientScreen.width / 2 + jx;
    int cy = clientScreen.y + clientScreen.height / 2 + jy;
    cx = clamp(cx, clientScreen.x + 2, clientScreen.x + clientScreen.width - 3);
    cy = clamp(cy, clientScreen.y + 2, clientScreen.y + clientScreen.height - 3);
    if (cfg.winSendInputMouse) {
      WinSendInputMouse.leftClickScreen(cx, cy);
      sleepMs(30 + r.nextInt(40));
    } else {
      robot.mouseMove(cx, cy);
      robot.delay(30 + r.nextInt(40));
      robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
      robot.delay(40 + r.nextInt(35));
      robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
  }

  /** Left-click at a specific screen coordinate with jitter and hold duration. */
  public void leftClickHoldAt(int screenX, int screenY, int jitterPx, int holdMs) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int jx = r.nextInt(-jitterPx, jitterPx + 1);
    int jy = r.nextInt(-jitterPx, jitterPx + 1);
    int sx = screenX + jx;
    int sy = screenY + jy;
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      SemiBackgroundMouse.backgroundClickHold(gameWindow.getHwnd(), sx, sy, holdMs);
      sleepMs(30 + r.nextInt(40));
      return;
    }
    beforePointer();
    if (cfg.winSendInputMouse) {
      WinSendInputMouse.leftClickHoldScreen(sx, sy, holdMs);
    } else {
      robot.mouseMove(sx, sy);
      robot.delay(30 + r.nextInt(40));
      robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
      robot.delay(holdMs);
      robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }
  }

  /** Right-click at a specific screen coordinate with jitter. */
  public void rightClickAt(int screenX, int screenY, int jitterPx) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int jx = r.nextInt(-jitterPx, jitterPx + 1);
    int jy = r.nextInt(-jitterPx, jitterPx + 1);
    int sx = screenX + jx;
    int sy = screenY + jy;
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      SemiBackgroundMouse.backgroundRightClick(gameWindow.getHwnd(), sx, sy);
      sleepMs(30 + r.nextInt(40));
      return;
    }
    beforePointer();
    if (cfg.winSendInputMouse) {
      WinSendInputMouse.rightClickScreen(sx, sy);
      sleepMs(30 + r.nextInt(40));
    } else {
      robot.mouseMove(sx, sy);
      robot.delay(30 + r.nextInt(40));
      robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
      robot.delay(40 + r.nextInt(35));
      robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
    }
  }

  /** Right-click at a specific screen coordinate with jitter, holding the button for {@code holdMs}. */
  public void rightClickHoldAt(int screenX, int screenY, int jitterPx, int holdMs) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int jx = r.nextInt(-jitterPx, jitterPx + 1);
    int jy = r.nextInt(-jitterPx, jitterPx + 1);
    int sx = screenX + jx;
    int sy = screenY + jy;
    if (cfg.postMessageKeys && gameWindow != null && gameWindow.isValid()) {
      SemiBackgroundMouse.backgroundRightClickHold(gameWindow.getHwnd(), sx, sy, holdMs);
      sleepMs(30 + r.nextInt(40));
      return;
    }
    beforePointer();
    if (cfg.winSendInputMouse) {
      WinSendInputMouse.rightClickScreen(sx, sy);
      sleepMs(30 + r.nextInt(40));
    } else {
      robot.mouseMove(sx, sy);
      robot.delay(30 + r.nextInt(40));
      robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
      robot.delay(holdMs);
      robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  public void maybeMistakeSteer(boolean wasLeft, int leftVk, int rightVk) {
    if (ThreadLocalRandom.current().nextInt(100) != 0) {
      return;
    }
    int wrong = wasLeft ? rightVk : leftVk;
    keyHoldMs(wrong, 35 + ThreadLocalRandom.current().nextInt(45));
  }

  public void humanGapMs(int min, int max) {
    int a = Math.min(min, max);
    int b = Math.max(min, max);
    sleepMs(a + ThreadLocalRandom.current().nextInt(b - a + 1));
  }

  private void beforeKeyboard() {
    if (!cfg.refocusGameWindowBeforeKeys || gameWindow == null || !gameWindow.isValid()) {
      return;
    }
    long t = System.currentTimeMillis();
    if (t - lastRefocusMs < cfg.refocusGameWindowMinIntervalMs) {
      return;
    }
    lastRefocusMs = t;
    if (cfg.winUseAttachThreadInputForFocus) {
      gameWindow.activateForKeyboardInput();
    } else {
      gameWindow.bringToForeground();
    }
    sleepMs(cfg.postRefocusDelayMs);
  }

  private void beforePointer() {
    if (!cfg.refocusGameWindowBeforeKeys || gameWindow == null || !gameWindow.isValid()) {
      return;
    }
    long t = System.currentTimeMillis();
    if (t - lastRefocusMs < cfg.refocusGameWindowMinIntervalMs) {
      return;
    }
    lastRefocusMs = t;
    if (cfg.winUseAttachThreadInputForFocus) {
      gameWindow.activateForKeyboardInput();
    } else {
      gameWindow.bringToForeground();
    }
    sleepMs(cfg.postRefocusDelayMs);
  }

  private static void sleepMs(int ms) {
    try {
      Thread.sleep(Math.max(0, ms));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}
