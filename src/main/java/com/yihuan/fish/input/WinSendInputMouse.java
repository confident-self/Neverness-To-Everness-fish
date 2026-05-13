package com.yihuan.fish.input;

import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinUser;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Absolute mouse click via {@code SendInput} (virtual desktop), for games that ignore {@link java.awt.Robot} mouse. */
public final class WinSendInputMouse {
  private static final Logger LOG = Logger.getLogger(WinSendInputMouse.class.getName());

  private static final int MOUSEEVENTF_MOVE = 0x0001;
  private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
  private static final int MOUSEEVENTF_LEFTUP = 0x0004;
  private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
  private static final int MOUSEEVENTF_RIGHTUP = 0x0010;
  private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
  private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;

  private WinSendInputMouse() {}

  /**
   * Clicks at screen coordinates while the game is in the background. Saves the real cursor
   * position, sends a SendInput click (which updates the system cursor state so the game's
   * GetCursorPos / DirectInput sees it), then restores the cursor. The flicker is only a few ms.
   */
  public static void backgroundLeftClick(int screenX, int screenY) {
    POINT old = new POINT();
    User32.INSTANCE.GetCursorPos(old);
    leftClickScreen(screenX, screenY);
    User32.INSTANCE.SetCursorPos(old.x, old.y);
  }

  public static void rightClickScreen(int screenX, int screenY) {
    int vx = User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN);
    int vy = User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN);
    int vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN);
    int vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN);
    if (vw <= 1 || vh <= 1) {
      vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSCREEN);
      vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYSCREEN);
      vx = 0;
      vy = 0;
    }
    int nx = (int) ((screenX - vx) * 65535L / Math.max(1, vw - 1));
    int ny = (int) ((screenY - vy) * 65535L / Math.max(1, vh - 1));
    nx = Math.max(0, Math.min(65535, nx));
    ny = Math.max(0, Math.min(65535, ny));

    int baseFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    WinUser.INPUT[] arr = (WinUser.INPUT[]) new WinUser.INPUT().toArray(3);
    fillMouse(arr[0], nx, ny, baseFlags);
    fillMouse(arr[1], nx, ny, baseFlags | MOUSEEVENTF_RIGHTDOWN);
    fillMouse(arr[2], nx, ny, baseFlags | MOUSEEVENTF_RIGHTUP);
    int cb = arr[0].size();
    DWORD sent = User32.INSTANCE.SendInput(new DWORD(3), arr, cb);
    if (sent == null || sent.intValue() != 3) {
      int v = sent == null ? -1 : sent.intValue();
      LOG.log(Level.WARNING, "SendInput mouse: expected 3 events, got {0}", v);
    }
  }

  /**
   * Left-click-and-hold at screen coordinates via SendInput, with pre-hover.
   * Sends MOVE, waits {@code hoverMs}, sends DOWN, waits {@code holdMs}, sends UP.
   */
  public static void leftClickHoldScreen(int screenX, int screenY, int holdMs, int hoverMs) {
    int vx = User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN);
    int vy = User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN);
    int vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN);
    int vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN);
    if (vw <= 1 || vh <= 1) {
      vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSCREEN);
      vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYSCREEN);
      vx = 0;
      vy = 0;
    }
    int nx = (int) ((screenX - vx) * 65535L / Math.max(1, vw - 1));
    int ny = (int) ((screenY - vy) * 65535L / Math.max(1, vh - 1));
    nx = Math.max(0, Math.min(65535, nx));
    ny = Math.max(0, Math.min(65535, ny));

    int baseFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    int cb = new WinUser.INPUT().size();

    // 1. Move only — hover before pressing
    WinUser.INPUT[] moveBatch = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
    fillMouse(moveBatch[0], nx, ny, baseFlags);
    DWORD sent = User32.INSTANCE.SendInput(new DWORD(1), moveBatch, cb);
    if (sent == null || sent.intValue() != 1) {
      LOG.log(Level.WARNING, "SendInput move: expected 1 event, got {0}", sent);
    }

    sleepMs(hoverMs);

    // 2. Down
    WinUser.INPUT[] downBatch = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
    fillMouse(downBatch[0], nx, ny, baseFlags | MOUSEEVENTF_LEFTDOWN);
    sent = User32.INSTANCE.SendInput(new DWORD(1), downBatch, cb);
    if (sent == null || sent.intValue() != 1) {
      LOG.log(Level.WARNING, "SendInput down: expected 1 event, got {0}", sent);
    }

    sleepMs(holdMs);

    // 3. Up
    WinUser.INPUT[] upBatch = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
    fillMouse(upBatch[0], nx, ny, baseFlags | MOUSEEVENTF_LEFTUP);
    sent = User32.INSTANCE.SendInput(new DWORD(1), upBatch, cb);
    if (sent == null || sent.intValue() != 1) {
      LOG.log(Level.WARNING, "SendInput up: expected 1 event, got {0}", sent);
    }
  }

  /**
   * Background left-click-and-hold: saves cursor, moves to target, clicks via SendInput
   * (which updates GetAsyncKeyState / DirectInput / Raw Input), then restores cursor.
   */
  public static void backgroundLeftClickHold(int screenX, int screenY, int holdMs, int hoverMs) {
    POINT old = new POINT();
    User32.INSTANCE.GetCursorPos(old);
    leftClickHoldScreen(screenX, screenY, holdMs, hoverMs);
    User32.INSTANCE.SetCursorPos(old.x, old.y);
  }

  private static void sleepMs(int ms) {
    if (ms <= 0) return;
    try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  public static void leftClickScreen(int screenX, int screenY) {
    int vx = User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN);
    int vy = User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN);
    int vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN);
    int vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN);
    if (vw <= 1 || vh <= 1) {
      vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSCREEN);
      vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYSCREEN);
      vx = 0;
      vy = 0;
    }
    int nx = (int) ((screenX - vx) * 65535L / Math.max(1, vw - 1));
    int ny = (int) ((screenY - vy) * 65535L / Math.max(1, vh - 1));
    nx = Math.max(0, Math.min(65535, nx));
    ny = Math.max(0, Math.min(65535, ny));

    int baseFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK;
    WinUser.INPUT[] arr = (WinUser.INPUT[]) new WinUser.INPUT().toArray(3);
    fillMouse(arr[0], nx, ny, baseFlags);
    fillMouse(arr[1], nx, ny, baseFlags | MOUSEEVENTF_LEFTDOWN);
    fillMouse(arr[2], nx, ny, baseFlags | MOUSEEVENTF_LEFTUP);
    int cb = arr[0].size();
    DWORD sent = User32.INSTANCE.SendInput(new DWORD(3), arr, cb);
    if (sent == null || sent.intValue() != 3) {
      int v = sent == null ? -1 : sent.intValue();
      LOG.log(Level.WARNING, "SendInput mouse: expected 3 events, got {0}", v);
    }
  }

  private static void fillMouse(WinUser.INPUT in, int nx, int ny, int flags) {
    in.type = new DWORD(WinUser.INPUT.INPUT_MOUSE);
    in.input.setType("mi");
    in.input.mi.dx = new LONG(nx);
    in.input.mi.dy = new LONG(ny);
    in.input.mi.mouseData = new DWORD(0);
    in.input.mi.dwFlags = new DWORD(flags);
    in.input.mi.time = new DWORD(0);
    in.input.mi.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
  }
}
