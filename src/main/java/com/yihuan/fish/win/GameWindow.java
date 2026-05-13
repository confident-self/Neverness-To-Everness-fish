package com.yihuan.fish.win;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.awt.Rectangle;

/** Windows-only: locate game client area by partial window title. */
public final class GameWindow {
  private final String titleContains;
  private HWND hwnd;

  public GameWindow(String titleContains) {
    this.titleContains = titleContains;
  }

  public boolean refresh() {
    // 缓存优先: 只要已找到的 HWND 仍然有效, 就不重新搜索
    // 防止浏览器标签页等非游戏窗口因标题包含"异环"被误匹配
    if (isValid()) {
      return true;
    }
    HWND found = findByTitleContains(titleContains);
    hwnd = found;
    return found != null;
  }

  public HWND getHwnd() {
    return hwnd;
  }

  public boolean isValid() {
    return hwnd != null && User32.INSTANCE.IsWindow(hwnd);
  }

  /** True if the game window is the current foreground window. */
  public boolean isForeground() {
    if (!isValid()) {
      return false;
    }
    HWND fg = User32.INSTANCE.GetForegroundWindow();
    return fg != null && fg.equals(hwnd);
  }

  /** Client area in screen coordinates (pixels). */
  public Rectangle clientScreenRect() {
    if (!isValid()) {
      return new Rectangle(0, 0, 0, 0);
    }
    RECT client = new RECT();
    User32.INSTANCE.GetClientRect(hwnd, client);
    POINT topLeft = new POINT(0, 0);
    WinUser32.INSTANCE.ClientToScreen(hwnd, topLeft);
    int w = client.right - client.left;
    int h = client.bottom - client.top;
    return new Rectangle(topLeft.x, topLeft.y, w, h);
  }

  /**
   * Returns the window's outer rect (including title bar / borders) in screen coordinates, or an
   * empty rectangle if invalid.
   */
  public Rectangle windowScreenRect() {
    if (!isValid()) {
      return new Rectangle(0, 0, 0, 0);
    }
    RECT r = new RECT();
    User32.INSTANCE.GetWindowRect(hwnd, r);
    return new Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top);
  }

  /**
   * Moves the window so its <b>client area</b> top-left lands at the given screen coordinates.
   * Compensates for window borders / title bar so the client position is exact.
   */
  public void moveToClientPos(int targetClientLeft, int targetClientTop) {
    if (!isValid()) {
      return;
    }
    Rectangle window = windowScreenRect();
    Rectangle client = clientScreenRect();
    if (window.width <= 0 || client.width <= 0) {
      return;
    }
    // Border offsets: difference between outer window rect and client rect positions.
    int borderL = client.x - window.x;
    int borderT = client.y - window.y;
    int borderR = (window.x + window.width) - (client.x + client.width);
    int borderB = (window.y + window.height) - (client.y + client.height);

    int newX = targetClientLeft - borderL;
    int newY = targetClientTop - borderT;
    int newW = window.width;   // keep existing size
    int newH = window.height;

    User32.INSTANCE.SetWindowPos(
        hwnd, null, newX, newY, newW, newH,
        WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE);
  }

  public void bringToForeground() {
    if (isValid()) {
      User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
      User32.INSTANCE.SetForegroundWindow(hwnd);
    }
  }

  /**
   * Stronger than {@link #bringToForeground()}: temporarily attaches this JVM thread to the game window
   * thread so {@code SetForegroundWindow} is allowed, then restores threads.
   */
  public void activateForKeyboardInput() {
    if (!isValid()) {
      return;
    }
    int tidTarget = User32.INSTANCE.GetWindowThreadProcessId(hwnd, null);
    if (tidTarget == 0) {
      bringToForeground();
      return;
    }
    int tidSelf = Kernel32.INSTANCE.GetCurrentThreadId();
    if (tidSelf == tidTarget) {
      User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
      User32.INSTANCE.SetForegroundWindow(hwnd);
      User32.INSTANCE.BringWindowToTop(hwnd);
      return;
    }
    DWORD dSelf = new DWORD(tidSelf);
    DWORD dTarget = new DWORD(tidTarget);
    User32.INSTANCE.AttachThreadInput(dSelf, dTarget, true);
    try {
      User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
      User32.INSTANCE.SetForegroundWindow(hwnd);
      User32.INSTANCE.BringWindowToTop(hwnd);
    } finally {
      User32.INSTANCE.AttachThreadInput(dSelf, dTarget, false);
    }
  }

  private static HWND findByTitleContains(String needle) {
    final HWND[] out = {null};
    final int ourPid = Kernel32.INSTANCE.GetCurrentProcessId();
    final IntByReference winPid = new IntByReference();
    User32.INSTANCE.EnumWindows(
        (hWnd, data) -> {
          if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
            return true;
          }
          // 排除我们自己进程的窗口（如 UI 界面 "异环自动钓鱼"）
          User32.INSTANCE.GetWindowThreadProcessId(hWnd, winPid);
          if (winPid.getValue() == ourPid) {
            return true;
          }
          char[] title = new char[512];
          int len = User32.INSTANCE.GetWindowText(hWnd, title, title.length);
          if (len <= 0) {
            return true;
          }
          String t = Native.toString(title).trim();
          if (t.contains(needle)) {
            out[0] = hWnd;
            return false;
          }
          return true;
        },
        Pointer.NULL);
    return out[0];
  }
}
