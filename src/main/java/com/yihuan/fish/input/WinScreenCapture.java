package com.yihuan.fish.input;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Captures a screen rectangle using Win32 GDI {@code BitBlt}. Operates in physical screen pixels,
 * matching the coordinate system of {@code ClientToScreen} — no DPI scaling mismatch like
 * {@link java.awt.Robot} can have on high-DPI displays.
 */
public final class WinScreenCapture {

  private WinScreenCapture() {}

  /** Captures the given screen rectangle into a TYPE_INT_RGB {@link BufferedImage}. */
  public static BufferedImage capture(Rectangle rect) {
    int w = rect.width;
    int h = rect.height;
    if (w <= 0 || h <= 0) {
      return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    }

    HDC screenDC = User32.INSTANCE.GetDC(null);
    HDC memDC = GDI32.INSTANCE.CreateCompatibleDC(screenDC);
    HBITMAP bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDC, w, h);
    HANDLE oldBitmap = GDI32.INSTANCE.SelectObject(memDC, bitmap);

    GDI32.INSTANCE.BitBlt(memDC, 0, 0, w, h, screenDC, rect.x, rect.y, GDI32.SRCCOPY);

    // Read pixel data via native memory buffer (GetDIBits expects a Pointer, not int[])
    int byteCount = w * h * 4;
    Memory mem = new Memory(byteCount);
    WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
    bmi.bmiHeader.biWidth = w;
    bmi.bmiHeader.biHeight = -h; // top-down
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
    bmi.bmiHeader.biSize = bmi.bmiHeader.size();

    GDI32.INSTANCE.GetDIBits(memDC, bitmap, 0, h, mem, bmi, WinGDI.DIB_RGB_COLORS);

    GDI32.INSTANCE.SelectObject(memDC, oldBitmap);
    GDI32.INSTANCE.DeleteObject(bitmap);
    GDI32.INSTANCE.DeleteDC(memDC);
    User32.INSTANCE.ReleaseDC(null, screenDC);

    // Copy from native memory into Java int[] for BufferedImage
    int[] pixels = new int[w * h];
    mem.read(0, pixels, 0, pixels.length);

    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    int[] raster = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
    System.arraycopy(pixels, 0, raster, 0, pixels.length);
    return img;
  }
}
