package com.yihuan.fish.input;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;

/**
 * Sends key events directly to a window's message queue via {@code PostMessage}.
 * Combined with {@link KeepAlive} (WM_ACTIVATE spoofing), this allows the game to
 * receive input even when it is not the foreground window.
 *
 * <p>LPARAM encoding:
 * <ul>
 *   <li>bits 0–15: repeat count
 *   <li>bits 16–23: scan code
 *   <li>bit 30: previous key state (0 = was up, 1 = was down)
 *   <li>bit 31: transition (0 = press, 1 = release)
 * </ul>
 */
public final class WinPostMessageKeys {

  private static final int MAPVK_VK_TO_VSC = 0;
  private static final int WM_LBUTTONDOWN = 0x0201;
  private static final int WM_LBUTTONUP = 0x0202;
  private static final int MK_LBUTTON = 0x0001;

  private WinPostMessageKeys() {}

  public static void keyDown(HWND hwnd, int vk, boolean preferScanCode) {
    int scan = scanCode(vk);
    // repeat=0, previous state=0 (was up), transition=0 (pressing)
    int lParam = (scan << 16);
    User32.INSTANCE.PostMessage(
        hwnd, WinUser.WM_KEYDOWN, new WPARAM(vk), new LPARAM(lParam));
  }

  public static void keyUp(HWND hwnd, int vk, boolean preferScanCode) {
    int scan = scanCode(vk);
    // repeat=1, previous state=1 (was down), transition=1 (releasing)
    int lParam = (scan << 16) | 1 | (1 << 30) | (1 << 31);
    User32.INSTANCE.PostMessage(
        hwnd, WinUser.WM_KEYUP, new WPARAM(vk), new LPARAM(lParam));
  }

  public static void keyDownUp(HWND hwnd, int vk, boolean preferScanCode) {
    keyDown(hwnd, vk, preferScanCode);
    try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    keyUp(hwnd, vk, preferScanCode);
  }

  // --- Mouse (client-relative coordinates) ---

  /** Post a left-button click at client-relative (x, y). */
  public static void mouseLeftClick(HWND hwnd, int clientX, int clientY) {
    LPARAM pos = new LPARAM((clientY << 16) | (clientX & 0xFFFF));
    User32.INSTANCE.PostMessage(hwnd, WM_LBUTTONDOWN,
        new WPARAM(MK_LBUTTON), pos);
    try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    User32.INSTANCE.PostMessage(hwnd, WM_LBUTTONUP,
        new WPARAM(0), pos);
  }

  private static int scanCode(int vk) {
    var hkl = User32.INSTANCE.GetKeyboardLayout(0);
    return User32.INSTANCE.MapVirtualKeyEx(vk, MAPVK_VK_TO_VSC, hkl) & 0xFF;
  }
}
