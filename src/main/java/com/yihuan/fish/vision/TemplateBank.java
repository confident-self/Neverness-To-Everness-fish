package com.yihuan.fish.vision;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TemplateBank {
  public final BufferedImage yellowMarker;
  public final BufferedImage greenZone;
  public final BufferedImage successScreen;
  /**
   * When this template matches the screen, the FIGHTING UI is active (fish has bitten). It
   * triggers the FIGHTING phase; once in FIGHTING, {@link #yellowMarker} + {@link #greenZone}
   * provide the steering target positions.
   */
  public final BufferedImage fightIndicator;
  public final BufferedImage noBaitPrompt;
  public final BufferedImage baitItem;
  public final BufferedImage baitWheelItem;
  public final BufferedImage fillUpBtn;
  public final BufferedImage changeBaitBtn;
  /** 加满后验证图 — 匹配成功说明加满点击已生效 */
  public final BufferedImage fillUpConfirm;

  public TemplateBank(Path imageDir) throws IOException {
    if (!Files.isDirectory(imageDir)) {
      throw new IOException("Missing image directory: " + imageDir.toAbsolutePath());
    }
    yellowMarker = load(imageDir.resolve("yellow_marker.png"));
    greenZone = load(imageDir.resolve("green_zone.png"));
    successScreen = load(imageDir.resolve("success_screen.png"));
    fightIndicator = load(imageDir.resolve("FIGHTING状态判断.png"));
    noBaitPrompt = load(imageDir.resolve("无料状态判断.png"));
    baitItem = load(imageDir.resolve("R_购买万能饵料.png"));
    baitWheelItem = load(imageDir.resolve("E_购买万能饵料.png"));
    fillUpBtn = load(imageDir.resolve("加满按钮.png"));
    changeBaitBtn = load(imageDir.resolve("E_更换鱼饵.png"));
    fillUpConfirm = load(imageDir.resolve("拉满.png"));
  }

  private static BufferedImage load(Path p) throws IOException {
    if (!Files.isRegularFile(p)) {
      throw new IOException("Missing template PNG: " + p.toAbsolutePath());
    }
    BufferedImage img = ImageIO.read(p.toFile());
    if (img == null) {
      throw new IOException("Could not decode PNG: " + p.toAbsolutePath());
    }
    return img;
  }
}
