package com.yihuan.fish;

import com.yihuan.fish.vision.TemplateBank;
import com.yihuan.fish.vision.TemplateMatcher;
import com.yihuan.fish.vision.TemplateMatcher.Match;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

public final class PhaseDetector {
  private static final Logger LOG = Logger.getLogger(PhaseDetector.class.getName());

  private final FishConfig cfg;
  private final TemplateBank bank;

  /** Last good fight geometry while templates briefly fail (see {@link FishConfig#fightTemplateReleaseFrames}). */
  private FightingTargets lastFightHold;

  private int fightMissFrames;

  public PhaseDetector(FishConfig cfg, TemplateBank bank) {
    this.cfg = cfg;
    this.bank = bank;
  }

  /** Fighting steering targets. greenLeft/greenRight from color detection; greenCx = midpoint. */
  public record FightingTargets(Match yellow, int greenLeft, int greenRight, int yellowCx, int greenCx) {}

  public record RawDetect(GamePhase phase, FightingTargets fighting) {}

  private static Rectangle roi(FishConfig c, BufferedImage img, double l, double t, double r, double b) {
    int w = img.getWidth();
    int h = img.getHeight();
    int x1 = clamp((int) Math.round(l * w), 0, w - 1);
    int y1 = clamp((int) Math.round(t * h), 0, h - 1);
    int x2 = clamp((int) Math.round(r * w), x1 + 1, w);
    int y2 = clamp((int) Math.round(b * h), y1 + 1, h);
    return new Rectangle(x1, y1, x2 - x1, y2 - y1);
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  public RawDetect detect(BufferedImage client) {
    double th = cfg.matchThreshold;

    // 1. SUCCESS: check first so we exit the success screen as soon as it appears.
    Rectangle succRoi = roi(cfg, client, cfg.successRoiL, cfg.successRoiT, cfg.successRoiR, cfg.successRoiB);
    Match succ =
        TemplateMatcher.matchBest(
            client, succRoi.x, succRoi.y, succRoi.width, succRoi.height, bank.successScreen, 2);

    // 2. FIGHTING latch: tolerate brief template dropouts to avoid FIGHTING ↔ READY oscillation.
    if (lastFightHold != null) {
      fightMissFrames++;
      if (fightMissFrames <= cfg.fightTemplateReleaseFrames) {
        return new RawDetect(GamePhase.FIGHTING, lastFightHold);
      }
      lastFightHold = null;
      fightMissFrames = 0;
    }

    // 3. FIGHTING entry: match fight indicator template, then locate yellow marker via
    //    template matching and green zone via color detection for steering.
    Rectangle fiRoi =
        roi(cfg, client, cfg.fightIndicatorRoiL, cfg.fightIndicatorRoiT, cfg.fightIndicatorRoiR, cfg.fightIndicatorRoiB);
    Match fi =
        TemplateMatcher.matchBest(
            client, fiRoi.x, fiRoi.y, fiRoi.width, fiRoi.height, bank.fightIndicator, 2);

    if (succ.score() >= th) {
      lastFightHold = null;
      fightMissFrames = 0;
      return new RawDetect(GamePhase.SUCCESS, null);
    }

    if (fi.score() >= th) {
      Rectangle fRoi = roi(cfg, client, cfg.fightRoiL, cfg.fightRoiT, cfg.fightRoiR, cfg.fightRoiB);
      Match y = TemplateMatcher.matchBest(client, fRoi.x, fRoi.y, fRoi.width, fRoi.height, bank.yellowMarker, 1);
      int[] gBounds = findGreenZone(client, fRoi);
      if (y.score() >= th && gBounds != null) {
        int ycx = TemplateMatcher.centerX(y, bank.yellowMarker);
        int gLeft = gBounds[0];
        int gRight = gBounds[1];
        int gcx = (gLeft + gRight) / 2;
        lastFightHold = new FightingTargets(y, gLeft, gRight, ycx, gcx);
      }
      fightMissFrames = 0;
      return new RawDetect(GamePhase.FIGHTING, lastFightHold);
    }

    // 4. Nothing matched — keep spamming F.
    return new RawDetect(GamePhase.READY, null);
  }

  /**
   * Scans the slider ROI for green pixels and returns [minX, maxX] bounding the green zone.
   * Returns null if too few green pixels are found (green zone not visible).
   */
  private int[] findGreenZone(BufferedImage img, Rectangle roi) {
    int w = roi.width;
    int h = roi.height;
    int[] pixels = new int[w * h];
    img.getRGB(roi.x, roi.y, w, h, pixels, 0, w);

    int minG = cfg.greenDetectMinG;
    double grRatio = cfg.greenDetectGRRatio;
    double gbRatio = cfg.greenDetectGBRatio;

    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int count = 0;

    for (int i = 0; i < pixels.length; i++) {
      int c = pixels[i];
      int r = (c >> 16) & 0xFF;
      int g = (c >> 8) & 0xFF;
      int b = c & 0xFF;
      if (g >= minG && g >= r * grRatio && g >= b * gbRatio) {
        int x = roi.x + (i % w);
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        count++;
      }
    }

    if (count < cfg.greenDetectMinPixels) {
      return null;
    }
    return new int[] {minX, maxX};
  }
}
