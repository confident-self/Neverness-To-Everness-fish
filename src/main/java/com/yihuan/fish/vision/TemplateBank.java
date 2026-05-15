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
  /** 货币不足提示 — 购买鱼饵时余额不够弹出的提示 */
  public final BufferedImage noCurrency;
  /** 出售确认弹窗 — 点击一键售卖后验证 */
  public final BufferedImage sellConfirmDialog;
  /** 轮盘鱼饵详情弹窗 — 点击目标鱼饵后出现 */
  public final BufferedImage baitWheelDetailPopup;
  /** 月卡领取页面 — 游戏5:00重置后弹窗, 检测后按ESC */
  public final BufferedImage monthlyCardClaim;
  /** 月卡领取确认页面 — 按ESC关闭月卡领取页后出现, 点击下方退出 */
  public final BufferedImage monthlyCardClaimConfirm;
  /** Pre-READY 界面的"开始钓鱼"按钮 */
  public final BufferedImage preReadyStartBtn;
  /** 鱼饵未选择提示 — 点击"开始钓鱼"后如果没装鱼饵会弹出 */
  public final BufferedImage baitNotSelected;

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
    noCurrency = load(imageDir.resolve("货币不足.png"));
    sellConfirmDialog = load(imageDir.resolve("一键出售确认弹窗.png"));
    baitWheelDetailPopup = load(imageDir.resolve("E_弹窗判断.png"));
    monthlyCardClaim = load(imageDir.resolve("月卡领取.png"));
    monthlyCardClaimConfirm = load(imageDir.resolve("月卡领取确认.png"));
    preReadyStartBtn = load(imageDir.resolve("开始钓鱼.png"));
    baitNotSelected = load(imageDir.resolve("鱼饵未选择.png"));
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
