package com.yihuan.fish.input;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

/**
 * Disables Quick Edit mode on the Windows console so that clicking the console
 * window does not block the Java process (stdout writes hang while Quick Edit
 * is selecting text).
 */
public final class WinConsole {

  /** Quick Edit is a console INPUT mode flag — must be cleared on the input handle. */
  private static final int STD_INPUT_HANDLE = -10;
  private static final int ENABLE_QUICK_EDIT_MODE = 0x0040;
  private static final int ENABLE_EXTENDED_FLAGS = 0x0080;

  private WinConsole() {}

  /** Disable Quick Edit mode so accidental clicks on the console don't block the process. */
  public static void disableQuickEdit() {
    HANDLE h = Kernel32Ext.INSTANCE.GetStdHandle(STD_INPUT_HANDLE);
    if (h == null) return;
    IntByReference mode = new IntByReference();
    if (!Kernel32Ext.INSTANCE.GetConsoleMode(h, mode)) return;
    int newMode = mode.getValue() & ~ENABLE_QUICK_EDIT_MODE;
    newMode |= ENABLE_EXTENDED_FLAGS;
    Kernel32Ext.INSTANCE.SetConsoleMode(h, newMode);
  }

  private interface Kernel32Ext extends Library {
    Kernel32Ext INSTANCE = Native.load("kernel32", Kernel32Ext.class);
    HANDLE GetStdHandle(int nStdHandle);
    boolean GetConsoleMode(HANDLE hConsoleHandle, IntByReference lpMode);
    boolean SetConsoleMode(HANDLE hConsoleHandle, int dwMode);
  }
}
