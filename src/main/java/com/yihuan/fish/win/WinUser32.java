package com.yihuan.fish.win;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Extra user32 entry points not always exposed on {@code User32.INSTANCE} across JNA versions. */
public interface WinUser32 extends StdCallLibrary {
  WinUser32 INSTANCE = Native.load("user32", WinUser32.class, W32APIOptions.DEFAULT_OPTIONS);

  boolean ClientToScreen(HWND hWnd, POINT lpPoint);

  short GetAsyncKeyState(int vKey);
}
