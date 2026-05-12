package com.yihuan.fish.vision;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;

import java.awt.image.BufferedImage;

import static org.bytedeco.opencv.global.opencv_core.CV_32FC1;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.minMaxLoc;
import static org.bytedeco.opencv.global.opencv_imgproc.TM_CCOEFF_NORMED;
import static org.bytedeco.opencv.global.opencv_imgproc.matchTemplate;

/**
 * Template matching via OpenCV {@code matchTemplate(TM_CCOEFF_NORMED)}.
 * Score in [−1, 1] (1 = perfect match).
 */
public final class TemplateMatcher {

  public record Match(double score, int x, int y) {
    public static final Match NONE = new Match(0, -1, -1);
  }

  public static Match matchBest(
      BufferedImage scene, int rx, int ry, int rw, int rh, BufferedImage tmpl, int step) {
    int tw = tmpl.getWidth();
    int th = tmpl.getHeight();
    if (tw < 2 || th < 2 || rw < tw || rh < th) {
      return Match.NONE;
    }

    Mat roiMat = null;
    Mat tmplMat = null;
    Mat result = null;
    try {
      roiMat = rgbToGray(scene, rx, ry, rw, rh);
      tmplMat = rgbToGray(tmpl, 0, 0, tw, th);

      int resultW = rw - tw + 1;
      int resultH = rh - th + 1;
      result = new Mat(resultH, resultW, CV_32FC1);

      matchTemplate(roiMat, tmplMat, result, TM_CCOEFF_NORMED);

      DoublePointer minVal = new DoublePointer(1);
      DoublePointer maxVal = new DoublePointer(1);
      Point minLoc = new Point();
      Point maxLoc = new Point();
      minMaxLoc(result, minVal, maxVal, minLoc, maxLoc, null);

      return new Match(maxVal.get(), rx + maxLoc.x(), ry + maxLoc.y());
    } finally {
      release(roiMat);
      release(tmplMat);
      release(result);
    }
  }

  public static Match matchBest(
      BufferedImage scene, int rx, int ry, int rw, int rh, BufferedImage tmpl) {
    return matchBest(scene, rx, ry, rw, rh, tmpl, 1);
  }

  private static Mat rgbToGray(BufferedImage img, int x, int y, int w, int h) {
    int[] pixels = new int[w * h];
    img.getRGB(x, y, w, h, pixels, 0, w);
    byte[] gray = new byte[w * h];
    for (int i = 0; i < pixels.length; i++) {
      int c = pixels[i];
      int r = (c >> 16) & 0xFF;
      int g = (c >> 8) & 0xFF;
      int b = c & 0xFF;
      gray[i] = (byte) (0.299 * r + 0.587 * g + 0.114 * b);
    }
    Mat mat = new Mat(h, w, CV_8UC1);
    mat.data().put(gray);
    return mat;
  }

  private static void release(Mat m) {
    if (m != null) {
      m.release();
    }
  }

  public static int centerX(Match m, BufferedImage tmpl) {
    return m.x() + tmpl.getWidth() / 2;
  }

  public static int centerY(Match m, BufferedImage tmpl) {
    return m.y() + tmpl.getHeight() / 2;
  }
}
