package com.yihuan.fish.input;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;

/**
 * Semi-background mouse: briefly moves the real cursor so the game's {@code GetCursorPos()}
 * returns the correct screen coordinate, then uses {@code PostMessage} to deliver the click
 * directly to the game's message queue — bypassing the window manager entirely.
 *
 * <h3>Why this works even when the game is covered</h3>
 * <ol>
 *   <li>{@code SetCursorPos} moves the cursor icon to the target screen coordinate.
 *       The system's internal cursor position is updated.</li>
 *   <li>The game calls {@code GetCursorPos()} → returns the target coordinate.
 *       {@code GetCursorPos} returns a point in screen space; it does NOT ask
 *       "which window is under this point." That question is only asked by the
 *       window manager during hit-testing of real hardware events.</li>
 *   <li>{@code PostMessage} drops {@code WM_LBUTTONDOWN/WM_LBUTTONUP} directly
 *       into the game's message queue. No hit-testing, no {@code WindowFromPoint},
 *       no window manager involvement. The game receives and processes the click.</li>
 * </ol>
 *
 * <h3>Why PostMessage can't activate windows</h3>
 * Window activation is triggered by hardware events from the kernel. PostMessage
 * bypasses the kernel and the window manager — it drops a message directly into
 * the window's queue without the hardware→kernel→raw-input-thread→window-manager
 * pipeline. So the window manager never considers the window "foreground."
 *
 * <h3>Why PostMessage mouse alone doesn't work (pure background)</h3>
 * The game ignores the client-relative coordinates carried in {@code WM_LBUTTONDOWN}'s
 * LPARAM. Instead it calls {@code GetCursorPos()} to get the real cursor position.
 * If the real cursor is in another app the user is using, the game sees the click
 * at a wrong position and discards it. Hence we must briefly move the real cursor.
 *
 * <h3>Three approaches comparison</h3>
 * <table>
 *   <tr><th>Approach</th><th>Cursor</th><th>Occlusion-safe</th><th>Game sees click</th><th>User can work</th></tr>
 *   <tr><td>Foreground (Robot)</td><td>Moves real cursor</td><td>No</td><td>Yes</td><td>No</td></tr>
 *   <tr><td>Semi-background</td><td>Flickers ~25ms</td><td>Yes</td><td>Yes</td><td>Yes (brief flicker)</td></tr>
 *   <tr><td>Pure background (PostMessage only)</td><td>No</td><td>Yes</td><td>No (GetCursorPos mismatch)</td><td>Yes</td></tr>
 * </table>
 *
 * <h3>Risk: DirectInput / Raw Input games</h3>
 * If the game reads mouse input via DirectInput (exclusive mode) or Raw Input
 * ({@code WM_INPUT}), it reads directly from the device driver. Neither PostMessage
 * nor SendInput can inject events the game will see. No known workaround exists
 * for pure-background mouse in that scenario.
 */
public final class SemiBackgroundMouse {

  private static final int WM_LBUTTONDOWN = 0x0201;
  private static final int WM_LBUTTONUP = 0x0202;
  private static final int MK_LBUTTON = 0x0001;
  private static final int WM_RBUTTONDOWN = 0x0204;
  private static final int WM_RBUTTONUP = 0x0205;
  private static final int MK_RBUTTON = 0x0002;

  /**
   * Delay after the click before restoring cursor. Must be long enough for the
   * game's message loop to pick up and process the click (typically 1 game frame
   * = ~16ms at 60fps), but short enough that the cursor flicker is imperceptible.
   */
  private static final int POST_CLICK_RESTORE_DELAY_MS = 25;

  private SemiBackgroundMouse() {}

  /**
   * Clicks at screen coordinates on the target window, even when it's minimized or
   * covered. Total cursor displacement: ~25ms.
   *
   * <p>Usage: find window → get client rect screen position → lightning click.
   * <pre>{@code
   *   Rectangle client = window.clientScreenRect();
   *   int sx = client.x + client.width / 2;
   *   int sy = client.y + client.height / 2;
   *   SemiBackgroundMouse.backgroundClick(gameWindow.getHwnd(), sx, sy);
   * }</pre>
   */
  public static void backgroundClick(HWND hwnd, int screenX, int screenY) {
    // 1. Save current cursor position
    POINT old = new POINT();
    User32.INSTANCE.GetCursorPos(old);

    // 2. Move the real cursor to the target.
    //    This updates the system's internal cursor state so that
    //    GetCursorPos() inside the game returns the correct position.
    User32.INSTANCE.SetCursorPos(screenX, screenY);

    // 3. Convert screen coords to client-relative for the LPARAM.
    POINT pt = new POINT();
    pt.x = screenX;
    pt.y = screenY;
    User32Extra.INSTANCE.ScreenToClient(hwnd, pt);

    LPARAM pos = new LPARAM((pt.y << 16) | (pt.x & 0xFFFF));
    User32.INSTANCE.PostMessage(hwnd, WM_LBUTTONDOWN, new WPARAM(MK_LBUTTON), pos);

    // 4. Brief gap between down and up so the game registers a distinct click.
    sleepMs(30);

    User32.INSTANCE.PostMessage(hwnd, WM_LBUTTONUP, new WPARAM(0), pos);

    // 5. Let the game process the click before restoring cursor.
    sleepMs(POST_CLICK_RESTORE_DELAY_MS);

    // 6. Restore cursor to where the user left it.
    User32.INSTANCE.SetCursorPos(old.x, old.y);
  }

  /**
   * Right-clicks at screen coordinates on the target window, even when minimized or covered.
   * Same approach as {@link #backgroundClick} but sends {@code WM_RBUTTONDOWN/UP}.
   */
  public static void backgroundRightClick(HWND hwnd, int screenX, int screenY) {
    POINT old = new POINT();
    User32.INSTANCE.GetCursorPos(old);

    User32.INSTANCE.SetCursorPos(screenX, screenY);

    POINT pt = new POINT();
    pt.x = screenX;
    pt.y = screenY;
    User32Extra.INSTANCE.ScreenToClient(hwnd, pt);

    LPARAM pos = new LPARAM((pt.y << 16) | (pt.x & 0xFFFF));
    User32.INSTANCE.PostMessage(hwnd, WM_RBUTTONDOWN, new WPARAM(MK_RBUTTON), pos);

    sleepMs(30);

    User32.INSTANCE.PostMessage(hwnd, WM_RBUTTONUP, new WPARAM(0), pos);

    sleepMs(POST_CLICK_RESTORE_DELAY_MS);
    User32.INSTANCE.SetCursorPos(old.x, old.y);
  }

  /**
   * Right-clicks at screen coordinates and holds for {@code holdMs} before releasing.
   * For interactions where the game needs a longer press to register a right-click
   * (e.g. shop item selection) vs a fast tap that might be misinterpreted as scroll.
   * Includes a small delay after cursor move so the game's GetCursorPos() catches up.
   */
  public static void backgroundRightClickHold(HWND hwnd, int screenX, int screenY, int holdMs) {
    POINT old = new POINT();
    User32.INSTANCE.GetCursorPos(old);

    User32.INSTANCE.SetCursorPos(screenX, screenY);
    sleepMs(50); // let the game's polling thread pick up the new cursor position

    POINT pt = new POINT();
    pt.x = screenX;
    pt.y = screenY;
    User32Extra.INSTANCE.ScreenToClient(hwnd, pt);

    LPARAM pos = new LPARAM((pt.y << 16) | (pt.x & 0xFFFF));
    User32.INSTANCE.PostMessage(hwnd, WM_RBUTTONDOWN, new WPARAM(MK_RBUTTON), pos);

    sleepMs(holdMs);

    User32.INSTANCE.PostMessage(hwnd, WM_RBUTTONUP, new WPARAM(0), pos);

    sleepMs(POST_CLICK_RESTORE_DELAY_MS);
    User32.INSTANCE.SetCursorPos(old.x, old.y);
  }

  /**
   * Moves cursor to target, presses left button, holds for {@code holdMs},
   * Moves cursor to target, presses left button, holds for {@code holdMs},
   * releases, then restores cursor. For drag operations or long-click interactions.
   */
  public static void backgroundClickHold(HWND hwnd, int screenX, int screenY, int holdMs) {
    POINT old = new POINT();
    User32.INSTANCE.GetCursorPos(old);

    User32.INSTANCE.SetCursorPos(screenX, screenY);

    POINT pt = new POINT();
    pt.x = screenX;
    pt.y = screenY;
    User32Extra.INSTANCE.ScreenToClient(hwnd, pt);

    LPARAM pos = new LPARAM((pt.y << 16) | (pt.x & 0xFFFF));
    User32.INSTANCE.PostMessage(hwnd, WM_LBUTTONDOWN, new WPARAM(MK_LBUTTON), pos);

    sleepMs(holdMs);

    User32.INSTANCE.PostMessage(hwnd, WM_LBUTTONUP, new WPARAM(0), pos);

    sleepMs(POST_CLICK_RESTORE_DELAY_MS);
    User32.INSTANCE.SetCursorPos(old.x, old.y);
  }

  private static void sleepMs(int ms) {
    try {
      Thread.sleep(Math.max(0, ms));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
