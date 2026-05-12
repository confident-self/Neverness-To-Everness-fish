package com.yihuan.fish.input;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;

/** JNA mapping for Win32 APIs not in the standard {@code User32} bindings. */
interface User32Extra extends Library {
  User32Extra INSTANCE = Native.load("user32", User32Extra.class);

  HDC GetWindowDC(HWND hWnd);
  boolean PrintWindow(HWND hwnd, HDC hdcBlt, int nFlags);
  boolean ScreenToClient(HWND hWnd, POINT lpPoint);
}
