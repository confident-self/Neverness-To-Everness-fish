package com.yihuan.fish.input;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically sends {@code WM_ACTIVATE(WA_ACTIVE)} to trick the game into
 * believing it is the foreground window. Without this, many games pause or
 * ignore {@code PostMessage} input once they detect they are inactive.
 */
public final class KeepAlive {

  private static final int WM_ACTIVATE = 0x0006;
  private static final int WA_ACTIVE = 1;

  private KeepAlive() {}

  /**
   * Starts a daemon thread that sends {@code WM_ACTIVATE} every 3 seconds.
   * Caller controls the lifecycle via {@code running}.
   */
  public static Thread start(HWND hwnd, AtomicBoolean running) {
    Thread t =
        new Thread(
            () -> {
              while (running.get()) {
                User32.INSTANCE.SendMessage(
                    hwnd,
                    WM_ACTIVATE,
                    new WPARAM(WA_ACTIVE),
                    new LPARAM(0));
                try {
                  Thread.sleep(3000);
                } catch (InterruptedException e) {
                  break;
                }
              }
            },
            "keep-alive");
    t.setDaemon(true);
    t.start();
    return t;
  }
}
