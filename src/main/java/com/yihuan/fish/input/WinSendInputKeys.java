package com.yihuan.fish.input;

import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Win32 {@code SendInput} for keys. Many games ignore {@link java.awt.Robot}; some also ignore pure
 * virtual-key {@code SendInput} but accept <b>scan-code</b> events ({@link #KEYEVENTF_SCANCODE}).
 */
public final class WinSendInputKeys {
  private static final Logger LOG = Logger.getLogger(WinSendInputKeys.class.getName());

  private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
  private static final int KEYEVENTF_KEYUP = 0x0002;
  private static final int KEYEVENTF_SCANCODE = 0x0008;

  /** {@code MapVirtualKeyEx}: map VK to scan code. */
  private static final int MAPVK_VK_TO_VSC = 0;

  private WinSendInputKeys() {}

  public static void keyDown(int vkAwt, boolean preferScanCode) {
    send(vkAwt & 0xFFFF, false, preferScanCode);
  }

  public static void keyUp(int vkAwt, boolean preferScanCode) {
    send(vkAwt & 0xFFFF, true, preferScanCode);
  }

  /**
   * Sends a key-down + key-up pair via {@code SendInput}. The INFO log includes the encoding mode
   * ({@code scancode=0x…} or {@code VK=0x… (scanCode failed)}) and the number of events actually
   * inserted. This is essential for diagnosing why a game ignores input: if the log shows
   * {@code sent=2} but the game doesn't react, the issue is scan-code vs. VK or the game using
   * DirectInput; if {@code sent=0}, SendInput itself is being blocked (e.g. UIPI).
   */
  public static void keyDownUp(int vkAwt, boolean preferScanCode) {
    int vk = vkAwt & 0xFFFF;
    if (vk == 0) {
      return;
    }
    WinUser.INPUT[] arr = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
    String modeDown = fill(arr[0], vk, false, preferScanCode);
    fill(arr[1], vk, true, preferScanCode);
    int sent = dispatch(2, arr);
    LOG.fine("SendInput keyDownUp VK=0x" + Integer.toHexString(vk) + " mode=" + modeDown + " sent=" + sent);
  }

  private static void send(int vk, boolean up, boolean preferScanCode) {
    WinUser.INPUT[] arr = (WinUser.INPUT[]) new WinUser.INPUT().toArray(1);
    fill(arr[0], vk, up, preferScanCode);
    dispatch(1, arr);
  }

  /** Returns a short label describing how the event was encoded. */
  private static String fill(WinUser.INPUT in, int vk, boolean up, boolean preferScanCode) {
    in.type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
    in.input.setType("ki");
    in.input.ki.time = new DWORD(0);
    in.input.ki.dwExtraInfo = new BaseTSD.ULONG_PTR(0);

    int flags = up ? KEYEVENTF_KEYUP : 0;
    if (preferScanCode) {
      var hkl = User32.INSTANCE.GetKeyboardLayout(0);
      int scan = User32.INSTANCE.MapVirtualKeyEx(vk, MAPVK_VK_TO_VSC, hkl) & 0xFF;
      if (scan != 0) {
        int scanEx = User32.INSTANCE.MapVirtualKeyEx(vk, WinUser.MAPVK_VK_TO_VSC_EX, hkl);
        if ((scanEx & 0xFF00) != 0) {
          flags |= KEYEVENTF_EXTENDEDKEY;
        }
        in.input.ki.wVk = new WORD(0);
        in.input.ki.wScan = new WORD(scan);
        flags |= KEYEVENTF_SCANCODE;
        in.input.ki.dwFlags = new DWORD(flags);
        return "scancode=0x" + Integer.toHexString(scan);
      }
    }
    in.input.ki.wVk = new WORD((short) vk);
    in.input.ki.wScan = new WORD(0);
    in.input.ki.dwFlags = new DWORD(flags);
    return "VK=0x" + Integer.toHexString(vk) + " (scanCode failed)";
  }

  /** Returns the number of events actually inserted. */
  private static int dispatch(int n, WinUser.INPUT[] arr) {
    int cb = arr[0].size();
    DWORD sent = User32.INSTANCE.SendInput(new DWORD(n), arr, cb);
    int count = sent == null ? 0 : sent.intValue();
    if (count != n) {
      LOG.log(Level.WARNING, "SendInput keyboard: expected {0} events, got {1}", new Object[] {n, count});
    }
    return count;
  }
}
