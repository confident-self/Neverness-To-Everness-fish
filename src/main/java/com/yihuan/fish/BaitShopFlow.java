package com.yihuan.fish;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.yihuan.fish.input.BotInput;
import com.yihuan.fish.vision.TemplateBank;
import com.yihuan.fish.vision.TemplateMatcher;
import com.yihuan.fish.vision.TemplateMatcher.Match;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import com.yihuan.fish.input.SemiBackgroundMouse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BaitShopFlow {
  private static final Logger LOG = Logger.getLogger(BaitShopFlow.class.getName());
  private static final int MAX_RETRIES = 3;
  private static final int STUCK_ESC_THRESHOLD = 15;

  public enum State {
    IDLE,
    CHECK_NO_BAIT,
    ENTER_SHOP,
    SELECT_BAIT_WHEEL,
    SELECT_BAIT,
    CLICK_FILL_UP,
    CLICK_PURCHASE,
    CLICK_CONFIRM,
    CLICK_DISMISS,
    EXIT_SHOP,
    /** 退出商城后截图验证是否真的回到了钓鱼界面 */
    VERIFY_EXIT
  }

  private final FishConfig cfg;
  private final TemplateBank bank;
  private final BotInput input;

  private State state = State.IDLE;
  private int stepDelayRemaining;
  private boolean wheelItemClicked;
  private boolean actionClickAttempted;
  private int wheelRetryCount;
  private boolean purchased;

  // 加满点击验证
  private boolean fillUpClicked;
  private int fillUpRetryCount;

  // 退出商城验证
  private int exitVerifyRetryCount;

  // 卡死脱离: 当商城流程结束后如果连续STUCK_ESC_THRESHOLD次
  // doCheckNoBait都无无料提示, 说明可能卡在某个界面, 按ESC脱离
  private boolean shopFlowWasActive;   // true=商城流程曾在运行
  private int stuckNoBaitCount;        // 连续无无料计数

  public BaitShopFlow(FishConfig cfg, TemplateBank bank, BotInput input) {
    this.cfg = cfg;
    this.bank = bank;
    this.input = input;
  }

  public boolean isActive() {
    return state != State.IDLE;
  }

  public State state() {
    return state;
  }

  public void onCastAttempt() {
    state = State.CHECK_NO_BAIT;
    stepDelayRemaining = 200;
    wheelItemClicked = false;
    actionClickAttempted = false;
    wheelRetryCount = 0;
    purchased = false;
    fillUpClicked = false;
    fillUpRetryCount = 0;
    exitVerifyRetryCount = 0;
  }

  /** 直接从轮盘选择装备鱼饵（用于 Pre-READY 页面鱼饵未选择的场景） */
  public void startWheelSelection() {
    state = State.SELECT_BAIT_WHEEL;
    stepDelayRemaining = 0;
    wheelItemClicked = false;
    actionClickAttempted = false;
    wheelRetryCount = 0;
    purchased = false; // false: 没饵就进商城买, 有饵就装备
    LOG.info("【选饵轮盘】启动轮盘选择鱼饵");
  }

  public void tick(BufferedImage shot, Rectangle client, HWND hwnd) {
    if (state == State.IDLE) return;

    if (stepDelayRemaining > 0) {
      stepDelayRemaining -= Math.max(1, cfg.fightTickGapMinMs);
      return;
    }

    try {
      switch (state) {
        case CHECK_NO_BAIT     -> doCheckNoBait(shot, client, hwnd);
        case ENTER_SHOP        -> doEnterShop(hwnd);
        case SELECT_BAIT_WHEEL -> doSelectBaitWheel(shot, client, hwnd);
        case SELECT_BAIT       -> doSelectBait(client, hwnd);
        case CLICK_FILL_UP     -> doClickFillUp(client, hwnd);
        case CLICK_PURCHASE    -> doClickPurchase(client, hwnd);
        case CLICK_CONFIRM     -> doClickConfirm(client, hwnd);
        case CLICK_DISMISS     -> doClickDismiss(client, hwnd);
        case EXIT_SHOP         -> doExitShop(hwnd);
        case VERIFY_EXIT       -> doVerifyExit(shot, client, hwnd);
        default -> reset();
      }
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "【BaitShopFlow】异常 state=" + state + ": " + e.getMessage(), e);
      reset();
    }
  }

  public void reset() {
    state = State.IDLE;
    stepDelayRemaining = 0;
    wheelItemClicked = false;
    actionClickAttempted = false;
    wheelRetryCount = 0;
    purchased = false;
    fillUpClicked = false;
    fillUpRetryCount = 0;
    exitVerifyRetryCount = 0;
    markFlowInactive();
  }

  private void markFlowInactive() {
    shopFlowWasActive = false;
    stuckNoBaitCount = 0;
  }

  // ================================================================
  //  选饵轮盘 — 步骤1: 点击鱼饵 / 步骤2: 点击动作按钮+验证
  // ================================================================

  private void doSelectBaitWheel(BufferedImage shot, Rectangle client, HWND hwnd) {
    if (!wheelItemClicked) {
      // --- 步骤1: 在轮盘中点击目标鱼饵 ---
      LOG.info("【选饵轮盘】步骤1: 在轮盘中匹配鱼饵...");
      shopFlowWasActive = true;
      int w = shot.getWidth();
      int h = shot.getHeight();
      int roiX = cfg.baitShopBaitWheelItemMarginLeft;
      int roiY = cfg.baitShopBaitWheelItemMarginTop;
      int roiW = w - cfg.baitShopBaitWheelItemMarginLeft - cfg.baitShopBaitWheelItemMarginRight;
      int roiH = h - cfg.baitShopBaitWheelItemMarginTop - cfg.baitShopBaitWheelItemMarginBottom;

      boolean found = false;
      if (roiW >= bank.baitWheelItem.getWidth() && roiH >= bank.baitWheelItem.getHeight()) {
        Match m = TemplateMatcher.matchBest(shot, roiX, roiY, roiW, roiH, bank.baitWheelItem, 2);
        if (m.score() >= cfg.baitShopBaitWheelItemThreshold) {
          int cx = client.x + m.x() + bank.baitWheelItem.getWidth() / 2;
          int cy = client.y + m.y() + bank.baitWheelItem.getHeight() / 2;
          LOG.info("【选饵轮盘】步骤1: 匹配到鱼饵 (" + cx + "," + cy + ") 分数="
              + String.format("%.2f", m.score()) + ", 点击中...");
          input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
          found = true;
        }
      }

      if (!found) {
        int cx = client.x + roiX + roiW / 2;
        int cy = client.y + roiY + roiH / 2;
        LOG.info("【选饵轮盘】步骤1: 使用固定位置 (" + cx + "," + cy + "), 点击中...");
        input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
      }

      wheelItemClicked = true;
      stepDelayRemaining = cfg.baitShopUiTransitionMs;
      return;
    }

    // --- 检测并关闭鱼饵详情弹窗（点击鱼饵后可能出现） ---
    if (isDetailPopupVisible(shot)) {
      LOG.info("【选饵轮盘】检测到鱼饵详情弹窗, 点击空白关闭");
      int dx = client.x + client.width / 2;
      int dy = client.y + (int) (client.height * 0.9);
      input.leftClickHoldAt(dx, dy, 0, 200);
      stepDelayRemaining = cfg.baitShopUiTransitionMs;
      return;
    }

    // --- 步骤2: 点击动作按钮(更换/购买) + 验证 ---
    if (!actionClickAttempted) {
      int w = shot.getWidth();
      int h = shot.getHeight();
      int roiX = cfg.baitShopWheelBuyBtnMarginLeft;
      int roiY = cfg.baitShopWheelBuyBtnMarginTop;
      int roiW2 = w - roiX - cfg.baitShopWheelBuyBtnMarginRight;
      int roiH2 = h - roiY - cfg.baitShopWheelBuyBtnMarginBottom;

      if (roiW2 <= 0 || roiH2 <= 0) {
        LOG.warning("【选饵轮盘】步骤2: 按钮区域无效 " + roiW2 + "x" + roiH2 + ", 检查边距配置");
        reset();
        return;
      }

      int bx = client.x + roiX + roiW2 / 2;
      int by = client.y + roiY + roiH2 / 2;
      LOG.info("【选饵轮盘】步骤2: 点击动作按钮 (" + bx + "," + by
          + ") (第" + (wheelRetryCount + 1) + "/" + MAX_RETRIES + "次)");
      input.leftClickHoldAt(bx, by, 0, cfg.baitShopWheelClickHoldMs);
      actionClickAttempted = true;
      stepDelayRemaining = cfg.baitShopUiTransitionMs;
      return;
    }

    // 验证: 检查点击后的结果
    if (isInShop(shot)) {
      LOG.info("【选饵轮盘】步骤2: ✓ 已进入商城页面");
      wheelItemClicked = false;
      actionClickAttempted = false;
      wheelRetryCount = 0;
      if (purchased) {
        LOG.warning("【选饵轮盘】异常: 购买后再次进入商城, ESC退出");
        emergencyExitFromWheel(hwnd);
      } else {
        advance(State.SELECT_BAIT, 0);
      }
      return;
    }

    if (isInWheel(shot)) {
      wheelRetryCount++;
      if (wheelRetryCount < MAX_RETRIES) {
        LOG.info("【选饵轮盘】步骤2: 仍在轮盘内, 点击可能未生效, 重试中 (第"
            + (wheelRetryCount + 1) + "/" + MAX_RETRIES + "次)");
        actionClickAttempted = false;
        stepDelayRemaining = 200;
      } else {
        LOG.warning("【选饵轮盘】步骤2: 已达最大重试次数, ESC紧急退出");
        emergencyExitFromWheel(hwnd);
      }
      return;
    }

    // 既不在商城也不在轮盘 → 先尝试卡死恢复
    if (tryResolveStuck(client, hwnd)) {
      return;
    }
    LOG.info("【选饵轮盘】步骤2: ✓ 鱼饵已装备, 返回钓鱼");
    wheelItemClicked = false;
    actionClickAttempted = false;
    wheelRetryCount = 0;
    if (purchased) purchased = false;
    markFlowInactive();
    reset();
  }

  private boolean isInShop(BufferedImage shot) {
    int w = shot.getWidth();
    int h = shot.getHeight();
    int rx = cfg.baitShopBaitItemMarginLeft;
    int ry = cfg.baitShopBaitItemMarginTop;
    int rw = w - rx - cfg.baitShopBaitItemMarginRight;
    int rh = h - ry - cfg.baitShopBaitItemMarginBottom;
    if (rw < bank.baitItem.getWidth() || rh < bank.baitItem.getHeight()) return false;
    Match m = TemplateMatcher.matchBest(shot, rx, ry, rw, rh, bank.baitItem, 2);
    return m.score() >= cfg.baitShopBaitItemThreshold;
  }

  private boolean isInWheel(BufferedImage shot) {
    int w = shot.getWidth();
    int h = shot.getHeight();
    int rx = cfg.baitShopBaitWheelItemMarginLeft;
    int ry = cfg.baitShopBaitWheelItemMarginTop;
    int rw = w - rx - cfg.baitShopBaitWheelItemMarginRight;
    int rh = h - ry - cfg.baitShopBaitWheelItemMarginBottom;
    if (rw < bank.baitWheelItem.getWidth() || rh < bank.baitWheelItem.getHeight()) return false;
    Match m = TemplateMatcher.matchBest(shot, rx, ry, rw, rh, bank.baitWheelItem, 2);
    return m.score() >= cfg.baitShopBaitWheelItemThreshold;
  }

  /** 检测轮盘鱼饵详情弹窗是否可见 */
  private boolean isDetailPopupVisible(BufferedImage shot) {
    int w = shot.getWidth();
    int h = shot.getHeight();
    int rx = cfg.baitWheelDetailPopupMarginLeft;
    int ry = cfg.baitWheelDetailPopupMarginTop;
    int rw = w - rx - cfg.baitWheelDetailPopupMarginRight;
    int rh = h - ry - cfg.baitWheelDetailPopupMarginBottom;
    if (rw < bank.baitWheelDetailPopup.getWidth() || rh < bank.baitWheelDetailPopup.getHeight()) return false;
    Match m = TemplateMatcher.matchBest(shot, rx, ry, rw, rh, bank.baitWheelDetailPopup, 2);
    return m.score() >= cfg.baitWheelDetailPopupThreshold;
  }

  private void emergencyExitFromWheel(HWND hwnd) {
    LOG.warning("【紧急逃生】ESC×2 退出轮盘, 返回钓鱼界面");
    input.keyTap(cfg.baitShopEscVk);
    input.humanGapMs(200, 350);
    input.keyTap(cfg.baitShopEscVk);
    wheelItemClicked = false;
    actionClickAttempted = false;
    wheelRetryCount = 0;
    purchased = false;
    markFlowInactive();
    reset();
  }

  /**
   * 截图检测当前是否卡在商城或轮盘界面.
   * 如果是则按ESC退出, 返回 true; 否则返回 false.
   */
  private boolean tryResolveStuck(Rectangle client, HWND hwnd) {
    BufferedImage shot = input.captureScreen(client);
    if (shot == null) return false;
    try {
      if (isInShop(shot)) {
        LOG.warning("【卡死恢复】检测到仍在商城页面, ESC×2 退出");
        input.keyTap(cfg.baitShopEscVk);
        input.humanGapMs(200, 350);
        input.keyTap(cfg.baitShopEscVk);
        return true;
      }
      if (isInWheel(shot)) {
        LOG.warning("【卡死恢复】检测到仍在轮盘页面, ESC退出");
        input.keyTap(cfg.baitShopEscVk);
        return true;
      }
      return false;
    } finally {
      shot.flush();
    }
  }

  /** 检查截图中的(554,335,557,334)区域是否匹配货币不足提示 */
  private boolean isNoCurrency(BufferedImage shot) {
    int w = shot.getWidth();
    int h = shot.getHeight();
    int rx = cfg.baitShopNoCurrencyMarginLeft;
    int ry = cfg.baitShopNoCurrencyMarginTop;
    int rw = w - rx - cfg.baitShopNoCurrencyMarginRight;
    int rh = h - ry - cfg.baitShopNoCurrencyMarginBottom;
    if (rw < bank.noCurrency.getWidth() || rh < bank.noCurrency.getHeight()) return false;
    Match m = TemplateMatcher.matchBest(shot, rx, ry, rw, rh, bank.noCurrency, 2);
    return m.score() >= cfg.baitShopNoCurrencyThreshold;
  }

  // ================================================================
  //  检测无料提示 — 含卡死脱离逻辑
  // ================================================================

  private void doCheckNoBait(BufferedImage shot, Rectangle client, HWND hwnd) {
    int w = shot.getWidth();
    int h = shot.getHeight();
    int roiX = cfg.baitShopNoBaitMarginLeft;
    int roiY = cfg.baitShopNoBaitMarginTop;
    int roiW = w - cfg.baitShopNoBaitMarginLeft - cfg.baitShopNoBaitMarginRight;
    int roiH = h - cfg.baitShopNoBaitMarginTop - cfg.baitShopNoBaitMarginBottom;

    if (roiW < bank.noBaitPrompt.getWidth() || roiH < bank.noBaitPrompt.getHeight()) {
      LOG.warning("【检测无料】区域太小: " + roiW + "x" + roiH + ", 检查边距配置");
      reset();
      return;
    }

    Match m = TemplateMatcher.matchBest(shot, roiX, roiY, roiW, roiH, bank.noBaitPrompt, 2);
    if (m.score() >= cfg.baitShopNoBaitThreshold) {
      // 检测到无料 → 正常进入购买流程
      LOG.info("【检测无料】✓ 检测到无料提示 (分数=" + String.format("%.2f", m.score()) + "), 进入购买流程");
      shopFlowWasActive = true;
      stuckNoBaitCount = 0;
      advance(State.ENTER_SHOP, 0);
    } else {
      // 无无料提示
      if (shopFlowWasActive) {
        // 商城流程刚运行过, 但现在检测不到无料 →
        // 可能卡在某个界面未正常返回钓鱼, 计数并准备ESC脱离
        stuckNoBaitCount++;
        if (stuckNoBaitCount >= STUCK_ESC_THRESHOLD) {
          LOG.warning("【检测无料】连续" + STUCK_ESC_THRESHOLD + "次无无料提示, 按ESC脱离卡死");
          input.keyTap(cfg.baitShopEscVk);
          markFlowInactive();
          reset();
          return;
        }
        // 前几次计数不打印日志, 避免刷屏; 只有达到关键节点才打印
        if (stuckNoBaitCount == 10) {
          LOG.info("【检测无料】连续10次无无料提示, 可能卡死, 准备ESC脱离...");
        }
      } else {
        // 正常钓鱼中, 啥也不做
      }
      reset();
    }
  }

  // ================================================================
  //  按E进入选饵轮盘
  // ================================================================

  private void doEnterShop(HWND hwnd) {
    LOG.info("【按E进轮盘】打开选饵轮盘");
    shopFlowWasActive = true;
    input.keyTap(java.awt.event.KeyEvent.VK_E);
    advance(State.SELECT_BAIT_WHEEL, cfg.baitShopUiTransitionMs);
  }

  // ================================================================
  //  商城选饵 — 匹配鱼饵 → 右键选中
  // ================================================================

  private void doSelectBait(Rectangle client, HWND hwnd) {
    BufferedImage shopShot = input.captureScreen(client);
    if (shopShot == null) {
      LOG.warning("【商城选饵】截图失败, 准备重试...");
      wheelRetryCount++;
      if (wheelRetryCount < MAX_RETRIES) {
        advance(State.SELECT_BAIT, 200);
      } else {
        LOG.warning("【商城选饵】截图多次失败, ESC紧急退出");
        emergencyExitFromWheel(hwnd);
      }
      return;
    }
    try {
      int w = shopShot.getWidth();
      int h = shopShot.getHeight();
      int roiX = cfg.baitShopBaitItemMarginLeft;
      int roiY = cfg.baitShopBaitItemMarginTop;
      int roiW = w - cfg.baitShopBaitItemMarginLeft - cfg.baitShopBaitItemMarginRight;
      int roiH = h - cfg.baitShopBaitItemMarginTop - cfg.baitShopBaitItemMarginBottom;

      if (roiW < bank.baitItem.getWidth() || roiH < bank.baitItem.getHeight()) {
        LOG.warning("【商城选饵】鱼饵区域太小: " + roiW + "x" + roiH);
        reset();
        return;
      }

      Match m = TemplateMatcher.matchBest(shopShot, roiX, roiY, roiW, roiH, bank.baitItem, 2);
      if (m.score() >= cfg.baitShopBaitItemThreshold) {
        int centerX = client.x + m.x() + bank.baitItem.getWidth() / 2;
        int centerY = client.y + m.y() + bank.baitItem.getHeight() / 2;
        LOG.info("【商城选饵】✓ 匹配到鱼饵 (" + centerX + "," + centerY
            + ") 分数=" + String.format("%.2f", m.score()) + ", 左键选中中...");
        input.leftClickHoldAt(centerX, centerY, 0, cfg.baitShopBaitItemRightClickHoldMs);
        wheelRetryCount = 0;
        advance(State.CLICK_FILL_UP, cfg.baitShopUiTransitionMs);
      } else {
        // 商城中没有鱼饵 → 检查是否还在轮盘(点击失败)
        if (isInWheel(shopShot)) {
          wheelRetryCount++;
          if (wheelRetryCount < MAX_RETRIES) {
            LOG.info("【商城选饵】仍在轮盘内, 退回重试动作按钮 (第"
                + (wheelRetryCount + 1) + "/" + MAX_RETRIES + "次)");
            actionClickAttempted = false;
            advance(State.SELECT_BAIT_WHEEL, 200);
          } else {
            LOG.warning("【商城选饵】已达最大重试次数, ESC紧急退出");
            emergencyExitFromWheel(hwnd);
          }
        } else {
          if (tryResolveStuck(client, hwnd)) {
            return;
          }
          LOG.info("【商城选饵】不在商城或轮盘, 判定鱼饵已装备, 返回钓鱼");
          wheelItemClicked = false;
          actionClickAttempted = false;
          wheelRetryCount = 0;
          if (purchased) purchased = false;
          markFlowInactive();
          reset();
        }
      }
    } finally {
      shopShot.flush();
    }
  }

  // ================================================================
  //  点击加满按钮
  // ================================================================

  private void doClickFillUp(Rectangle client, HWND hwnd) {
    if (!fillUpClicked) {
      // 阶段1: 匹配加满按钮.png → 点击匹配位置
      BufferedImage shot = input.captureScreen(client);
      if (shot == null) {
        LOG.warning("【点击加满】截图失败, 跳过购买");
        fillUpClicked = false;
        fillUpRetryCount = 0;
        advance(State.SELECT_BAIT, cfg.baitShopUiTransitionMs);
        return;
      }
      int w = shot.getWidth();
      int h = shot.getHeight();
      int roiX = cfg.baitShopFillUpBtnMarginLeft;
      int roiY = cfg.baitShopFillUpBtnMarginTop;
      int roiW = w - roiX - cfg.baitShopFillUpBtnMarginRight;
      int roiH = h - roiY - cfg.baitShopFillUpBtnMarginBottom;

      boolean clicked = false;
      if (roiW >= bank.fillUpBtn.getWidth() && roiH >= bank.fillUpBtn.getHeight()) {
        Match m = TemplateMatcher.matchBest(shot, roiX, roiY, roiW, roiH, bank.fillUpBtn, 2);
        if (m.score() >= cfg.baitShopFillUpBtnThreshold) {
          int cx = client.x + m.x() + bank.fillUpBtn.getWidth() / 2;
          int cy = client.y + m.y() + bank.fillUpBtn.getHeight() / 2;
          LOG.info("【点击加满】✓ 匹配到按钮 (" + cx + "," + cy + ") 分数="
              + String.format("%.2f", m.score()) + " (第" + (fillUpRetryCount + 1) + "/" + MAX_RETRIES + "次)");
          SemiBackgroundMouse.backgroundClickHold(hwnd, cx, cy, cfg.baitShopFillUpBtnHoldMs, 0);
          clicked = true;
        }
      }

      if (!clicked) {
        fillUpRetryCount++;
        if (fillUpRetryCount < MAX_RETRIES) {
          LOG.info("【点击加满】未匹配到按钮, 重试 (第" + (fillUpRetryCount + 1) + "/" + MAX_RETRIES + "次)");
          stepDelayRemaining = 200;
        } else {
          debugSave(shot, "fillup_nobtn", "加满按钮.png 在ROI内未匹配到");
          LOG.warning("【点击加满】匹配不到按钮, ESC退出商城");
          input.keyTap(cfg.baitShopEscVk);
          input.humanGapMs(200, 350);
          fillUpClicked = false;
          fillUpRetryCount = 0;
          advance(State.SELECT_BAIT, cfg.baitShopUiTransitionMs);
        }
        shot.flush();
        return;
      }
      shot.flush();
      fillUpClicked = true;
      stepDelayRemaining = cfg.baitShopUiTransitionMs;
      return;
    }

    // 阶段2: 用拉满.png验证加满是否生效
    BufferedImage verifyShot = input.captureScreen(client);
    if (verifyShot == null) {
      LOG.warning("【点击加满】验证截图失败, 跳过验证");
      fillUpClicked = false;
      fillUpRetryCount = 0;
      advance(State.CLICK_PURCHASE, cfg.baitShopUiTransitionMs);
      return;
    }
    try {
      int w = verifyShot.getWidth();
      int h = verifyShot.getHeight();
      int rx = cfg.baitShopFillUpConfirmMarginLeft;
      int ry = cfg.baitShopFillUpConfirmMarginTop;
      int rw = w - rx - cfg.baitShopFillUpConfirmMarginRight;
      int rh = h - ry - cfg.baitShopFillUpConfirmMarginBottom;

      boolean verified = false;
      if (rw >= bank.fillUpConfirm.getWidth() && rh >= bank.fillUpConfirm.getHeight()) {
        Match m = TemplateMatcher.matchBest(verifyShot, rx, ry, rw, rh, bank.fillUpConfirm, 2);
        if (m.score() >= cfg.baitShopFillUpConfirmThreshold) {
          LOG.info("【点击加满】✓ 拉满图标验证通过 (分数=" + String.format("%.2f", m.score()) + "), 进入购买步骤");
          verified = true;
        }
      }

      if (verified) {
        fillUpClicked = false;
        fillUpRetryCount = 0;
        advance(State.CLICK_PURCHASE, cfg.baitShopUiTransitionMs);
      } else {
        // 检查是否货币不足弹窗
        if (isNoCurrency(verifyShot)) {
          LOG.warning("【点击加满】检测到货币不足弹窗, ESC关闭, 标记售卖前置");
          input.keyTap(cfg.baitShopEscVk);
          input.humanGapMs(200, 350);
          fillUpClicked = false;
          fillUpRetryCount = 0;
          cfg.needsSellBeforeBait = true;
          markFlowInactive();
          reset();
        } else {
          fillUpRetryCount++;
          if (fillUpRetryCount < MAX_RETRIES) {
            LOG.info("【点击加满】验证未通过, 重试点击 (第" + (fillUpRetryCount + 1) + "/" + MAX_RETRIES + "次)");
            fillUpClicked = false;
            stepDelayRemaining = 200;
          } else {
            debugSave(verifyShot, "fillup_noverify", "拉满.png 在确认ROI内未匹配到");
            LOG.warning("【点击加满】已达最大重试次数, ESC返回商城");
            input.keyTap(cfg.baitShopEscVk);
            input.humanGapMs(200, 350);
            fillUpClicked = false;
            fillUpRetryCount = 0;
            advance(State.SELECT_BAIT, cfg.baitShopUiTransitionMs);
          }
        }
      }
    } finally {
      verifyShot.flush();
    }
  }

  // ================================================================
  //  点击购买按钮
  // ================================================================

  private void doClickPurchase(Rectangle client, HWND hwnd) {
    int w = client.width;
    int h = client.height;
    int roiX = cfg.baitShopPurchaseBtnMarginLeft;
    int roiY = cfg.baitShopPurchaseBtnMarginTop;
    int roiW = w - cfg.baitShopPurchaseBtnMarginLeft - cfg.baitShopPurchaseBtnMarginRight;
    int roiH = h - cfg.baitShopPurchaseBtnMarginTop - cfg.baitShopPurchaseBtnMarginBottom;

    if (roiW <= 0 || roiH <= 0) {
      LOG.warning("【点击购买】按钮区域无效 " + roiW + "x" + roiH + ", 检查边距配置, 跳过");
      advance(State.EXIT_SHOP, cfg.baitShopUiTransitionMs);
      return;
    }

    int cx = client.x + roiX + roiW / 2;
    int cy = client.y + roiY + roiH / 2;
    LOG.info("【点击购买】点击商城购买按钮 (" + cx + "," + cy + ")");
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    LOG.info("【点击购买】✓ 完成, 进入确认步骤");
    advance(State.CLICK_CONFIRM, cfg.baitShopUiTransitionMs);
  }

  // ================================================================
  //  点击确认按钮
  // ================================================================

  private void doClickConfirm(Rectangle client, HWND hwnd) {
    int w = client.width;
    int h = client.height;
    int roiX = cfg.baitShopConfirmBtnMarginLeft;
    int roiY = cfg.baitShopConfirmBtnMarginTop;
    int roiW = w - cfg.baitShopConfirmBtnMarginLeft - cfg.baitShopConfirmBtnMarginRight;
    int roiH = h - cfg.baitShopConfirmBtnMarginTop - cfg.baitShopConfirmBtnMarginBottom;

    if (roiW <= 0 || roiH <= 0) {
      LOG.warning("【点击确认】按钮区域无效 " + roiW + "x" + roiH + ", 检查边距配置, 跳过");
      advance(State.EXIT_SHOP, cfg.baitShopUiTransitionMs);
      return;
    }

    int cx = client.x + roiX + roiW / 2;
    int cy = client.y + roiY + roiH / 2;
    LOG.info("【点击确认】点击确认按钮 (" + cx + "," + cy + ")");
    input.leftClickHoldAt(cx, cy, 0, cfg.baitShopWheelClickHoldMs);
    LOG.info("【点击确认】✓ 完成, 退出商城");
    advance(State.EXIT_SHOP, cfg.baitShopUiTransitionMs);
  }

  // ================================================================
  //  关闭弹窗(随机空白区域)
  // ================================================================

  private void doClickDismiss(Rectangle client, HWND hwnd) {
    ThreadLocalRandom r = ThreadLocalRandom.current();
    int x1 = client.x + 10;
    int x2 = client.x + client.width - 10;
    int y1 = client.y + (int) (client.height * (1.0 - cfg.baitShopDismissBottomAreaPct));
    int y2 = client.y + (int) (client.height * 0.96);
    int dx = r.nextInt(x1, x2 + 1);
    int dy = r.nextInt(y1, y2 + 1);
    LOG.info("【关闭弹窗】点击空白区域 (" + dx + "," + dy + ")");
    input.leftClickHoldAt(dx, dy, 0, cfg.baitShopWheelClickHoldMs);
    advance(State.EXIT_SHOP, cfg.baitShopUiTransitionMs);
  }

  // ================================================================
  //  退出商城 → 重新进轮盘装备
  // ================================================================

  private void doExitShop(HWND hwnd) {
    LOG.info("【退出商城】ESC×2 关闭购买成功页");
    input.keyTap(cfg.baitShopEscVk);
    input.humanGapMs(200, 350);
    input.keyTap(cfg.baitShopEscVk);
    exitVerifyRetryCount = 0;
    advance(State.VERIFY_EXIT, cfg.baitShopUiTransitionMs);
  }

  /** 验证是否已退出商城, 如果还在则继续ESC */
  private void doVerifyExit(BufferedImage shot, Rectangle client, HWND hwnd) {
    if (isInShop(shot)) {
      exitVerifyRetryCount++;
      if (exitVerifyRetryCount < MAX_RETRIES) {
        LOG.info("【验证退出】仍在商城, 再次ESC (第" + (exitVerifyRetryCount + 1) + "/" + MAX_RETRIES + "次)");
        input.keyTap(cfg.baitShopEscVk);
        input.humanGapMs(200, 350);
        stepDelayRemaining = cfg.baitShopUiTransitionMs;
      } else {
        LOG.warning("【验证退出】多次ESC仍在商城, 强制重置");
        markFlowInactive();
        reset();
      }
    } else {
      LOG.info("【验证退出】✓ 已退出商城, 返回钓鱼");
      markFlowInactive();
      reset();
    }
  }

  // ================================================================

  /** Save a debug screenshot when something unexpected happens. */
  private void debugSave(BufferedImage img, String tag, String detail) {
    try {
      File out = cfg.imageDir.resolve("debug_" + tag + ".png").toFile();
      ImageIO.write(img, "PNG", out);
      LOG.info("【调试截图】已保存 " + out.getName() + " — " + detail);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "【调试截图】保存失败", e);
    }
  }

  private void advance(State next, int delayMs) {
    LOG.info("【状态】" + state + " → " + next + "  (等待" + delayMs + "ms)");
    state = next;
    stepDelayRemaining = delayMs;
  }

  private void logState(String msg) {
    LOG.info("【" + state + "】" + msg);
  }
}
