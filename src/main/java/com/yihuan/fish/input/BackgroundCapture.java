package com.yihuan.fish.input;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Background window capture via {@code PrintWindow(PW_RENDERFULLCONTENT)}.
 * Works even when the window is minimized or covered — no screen DC required.
 * Requires administrator privileges for DirectX-rendered windows.
 */
public final class BackgroundCapture {

  /** Render full DirectX / OpenGL content, not just the GDI surface. */
  private static final int PW_RENDERFULLCONTENT = 3;

  private BackgroundCapture() {}

  /**
   * Captures the client area of the given window, even if it is minimized or behind other windows.
   * Returns {@code null} if PrintWindow fails.
   */
  public static BufferedImage captureClient(HWND hwnd) {
    RECT rect = new RECT();
    User32.INSTANCE.GetClientRect(hwnd, rect);
    int w = rect.right - rect.left;
    int h = rect.bottom - rect.top;
    if (w <= 0 || h <= 0) {
      return null;
    }
    return capture(hwnd, w, h);
  }

  private static BufferedImage capture(HWND hwnd, int w, int h) {
    HDC winDC = User32Extra.INSTANCE.GetWindowDC(hwnd);
    HDC memDC = GDI32.INSTANCE.CreateCompatibleDC(winDC);
    HBITMAP bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(winDC, w, h);
    HANDLE oldObj = GDI32.INSTANCE.SelectObject(memDC, bitmap);

    boolean ok = User32Extra.INSTANCE.PrintWindow(hwnd, memDC, PW_RENDERFULLCONTENT);

    BufferedImage img = null;
    if (ok) {
      WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
      bmi.bmiHeader.biWidth = w;
      bmi.bmiHeader.biHeight = -h;
      bmi.bmiHeader.biPlanes = 1;
      bmi.bmiHeader.biBitCount = 32;
      bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
      bmi.bmiHeader.biSize = bmi.bmiHeader.size();

      int byteCount = w * h * 4;
      Memory mem = new Memory(byteCount);
      GDI32.INSTANCE.GetDIBits(memDC, bitmap, 0, h, mem, bmi, WinGDI.DIB_RGB_COLORS);

      int[] pixels = new int[w * h];
      mem.read(0, pixels, 0, pixels.length);

      img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      int[] raster = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
      System.arraycopy(pixels, 0, raster, 0, pixels.length);
    }

    GDI32.INSTANCE.SelectObject(memDC, oldObj);
    GDI32.INSTANCE.DeleteObject(bitmap);
    GDI32.INSTANCE.DeleteDC(memDC);
    User32.INSTANCE.ReleaseDC(hwnd, winDC);
    return img;
  }
}
