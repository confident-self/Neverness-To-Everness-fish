package com.yihuan.fish;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Tunables: replace templates under {@code image/} and adjust ROI if your HUD differs. */
public final class FishConfig {
  public Path imageDir = Paths.get("image");

  public String windowTitleSubstring = "异环";

  public int expectedClientWidth = 1280;
  public int expectedClientHeight = 720;

  /**
   * Target position of the game window's client area, as fractions of the primary screen.
   * Before READY actions, the bot checks whether the client is at this position; if not, it moves
   * the window via {@code SetWindowPos}. Using relative positions avoids hard-coding screen
   * coordinates that break across different monitors or resolutions.
   */
  public double windowTargetLeftPct = 0.10;
  public double windowTargetTopPct = 0.10;

  /** Max allowed deviation in pixels before the window is repositioned. */
  public int windowPositionTolerancePx = 8;

  /** NCC score in [0,1]; raise if false positives, lower if misses. */
  public double matchThreshold = 0.50;

  /**
   * Frames the same raw phase must repeat before {@code stable} follows {@code raw}. If templates
   * flicker near threshold, use {@code 1} so the bot still sends keys; increase if you see false
   * positives.
   */
  public int phaseStableFrames = 1;

  /**
   * While latched in FIGHTING, tolerate this many consecutive frames where yellow/green templates drop
   * below threshold before leaving FIGHTING (reduces raw FIGHTING &lt;-&gt; WAITING_BITE oscillation that
   * keeps {@code stable} stuck on IDLE when {@link #phaseStableFrames} &gt; 1).
   */
  public int fightTemplateReleaseFrames = 4;

  /** Periodic INFO heartbeat while the bot loop runs (raw/stable/prep, client size). */
  public int infoHeartbeatIntervalMs = 2500;

  /**
   * If true, use {@code PostMessage} to send keys directly to the game HWND. The game can be in the
   * background while the bot presses keys — user focus and keyboard input are unaffected.
   */
  public boolean postMessageKeys = true;

  /**
   * If true (and {@link #postMessageKeys} is false), keyboard uses Win32 {@code SendInput} instead of
   * {@link java.awt.Robot}. Many games ignore Robot keys but accept SendInput.
   */
  public boolean winSendInputKeys = true;

  /**
   * Before each keyboard action, call {@code SetForegroundWindow} on the game (throttled). Helps when the
   * game HWND is “foreground” but keyboard focus is still on another control.
   */
  public boolean refocusGameWindowBeforeKeys = true;

  public int refocusGameWindowMinIntervalMs = 400;

  /** Sleep after refocus before sending keys (ms). */
  public int postRefocusDelayMs = 70;

  /**
   * If true (with {@link #winSendInputKeys}), send scan-code keyboard events. Some games ignore VK-only
   * {@code SendInput} but accept scancodes.
   */
  public boolean winSendInputUseScanCode = true;

  /**
   * Use {@code AttachThreadInput} so {@code SetForegroundWindow} can take effect when another thread owns
   * the foreground queue (common with game + overlay).
   */
  public boolean winUseAttachThreadInputForFocus = true;

  /** If true, success clicks use {@code SendInput} mouse instead of {@link java.awt.Robot}. */
  public boolean winSendInputMouse = true;

  public int fightingCaptureIntervalMs = 25;
  public int idlePollIntervalMs = 350;

  /** Tick-to-tick gap between FIGHTING steering checks when holding a key (ms). */
  public int fightTickGapMinMs = 10;
  public int fightTickGapMaxMs = 22;

  /**
   * READY spam F interval range (ms). Randomised within [75, 99] to avoid looking like a fixed-rate
   * macro — most anti-cheat systems flag perfectly periodic input. The upper bound is under 100ms to
   * ensure the bot reacts to a fish bite within at most one tick of latency.
   */
  public int prepSpamFMinMs = 75;
  public int prepSpamFMaxMs = 99;

  public int humanPauseMinMs = 1200;
  public int humanPauseMaxMs = 3000;

  public int postActionRestMinMs = 400;
  public int postActionRestMaxMs = 900;

  public double msPerPixelSteer = 1.2;
  public int steerMinMs = 15;
  public int steerMaxMs = 420;
  public int steerDeadzonePx = 3;

  // --- Green zone color detection (replaces green_zone.png template matching) ---
  public int greenDetectMinG = 150;
  public double greenDetectGRRatio = 1.3;
  public double greenDetectGBRatio = 1.1;
  public int greenDetectMinPixels = 10;

  public int fightingStallResetMs = 32_000;

  public int clickJitterPx = 15;

  // --- Bait shop purchase flow (no-bait detection + automated purchase) ---

  /**
   * No-bait prompt detection ROI — pixel margins from client area edges.
   * The cropping region inside the client screenshot: left margin 525, right margin 525,
   * top margin 327, bottom margin 330.
   */
  public int baitShopNoBaitMarginLeft = 525;
  public int baitShopNoBaitMarginRight = 525;
  public int baitShopNoBaitMarginTop = 327;
  public int baitShopNoBaitMarginBottom = 330;

  /** Template match threshold for the no-bait prompt. */
  public double baitShopNoBaitThreshold = 0.70;

  /**
   * Bait item template matching ROI — pixel margins from client area edges.
   * The region where the purchasable bait items appear in the shop UI.
   */
  public int baitShopBaitItemMarginLeft = 33;
  public int baitShopBaitItemMarginRight = 841;
  public int baitShopBaitItemMarginTop = 87;
  public int baitShopBaitItemMarginBottom = 477;

  /** Template match threshold for the bait item (R_购买万能饵料.png). */
  public double baitShopBaitItemThreshold = 0.70;

  /** How long to hold the right-click on the bait item (ms). Longer press avoids accidental scroll. */
  public int baitShopBaitItemRightClickHoldMs = 800;

  /**
   * Bait wheel (E key) — item selection ROI margins (pixels from client edges).
   * The region where the target bait appears in the wheel UI after pressing E.
   */
  public int baitShopBaitWheelItemMarginLeft = 432;
  public int baitShopBaitWheelItemMarginRight = 768;
  public int baitShopBaitWheelItemMarginTop = 317;
  public int baitShopBaitWheelItemMarginBottom = 324;

  /** Template match threshold for the bait wheel item (E_购买万能饵料.png). */
  public double baitShopBaitWheelItemThreshold = 0.70;

  /** 轮盘鱼饵详情弹窗 — 点击目标鱼饵后可能出现, 用 /image/E_弹窗判断.png 检测 */
  public int baitWheelDetailPopupMarginLeft = 456;
  public int baitWheelDetailPopupMarginRight = 460;
  public int baitWheelDetailPopupMarginTop = 155;
  public int baitWheelDetailPopupMarginBottom = 432;
  public double baitWheelDetailPopupThreshold = 0.70;

  /**
   * Bait wheel purchase button ROI margins (pixels from client edges).
   * Clicked after selecting the bait in the wheel to enter the shop.
   */
  public int baitShopWheelBuyBtnMarginLeft = 885;
  public int baitShopWheelBuyBtnMarginRight = 389;
  public int baitShopWheelBuyBtnMarginTop = 452;
  public int baitShopWheelBuyBtnMarginBottom = 230;

  /** Left-click hold duration for wheel / button clicks (ms). */
  public int baitShopWheelClickHoldMs = 500;

  /**
   * "更换鱼饵" button detection in the wheel — ROI margins and threshold.
   * When this button is detected after selecting a bait, it means the bait is
   * already owned and just needs to be equipped (no shop needed).
   */
  public int baitShopChangeBaitBtnMarginLeft = 760;
  public int baitShopChangeBaitBtnMarginRight = 480;
  public int baitShopChangeBaitBtnMarginTop = 455;
  public int baitShopChangeBaitBtnMarginBottom = 238;
  public double baitShopChangeBaitBtnThreshold = 0.70;

  /**
   * "加满" button in the shop — ROI margins (pixels from client edges).
   * Can also match via /image/加满按钮.png template.
   */
  public int baitShopFillUpBtnMarginLeft = 1199;
  public int baitShopFillUpBtnMarginRight = 48;
  public int baitShopFillUpBtnMarginTop = 615;
  public int baitShopFillUpBtnMarginBottom = 70;

  /** Template match threshold for 加满按钮.png. */
  public double baitShopFillUpBtnThreshold = 0.70;

  /** 加满按钮点击时长(ms), 比普通按钮稍长以避免概率性无响应. */
  public int baitShopFillUpBtnHoldMs = 250;

  /** 点击前悬停时间(ms), 模拟真人操作, 避免游戏防脚本检测. */
  public int preClickHoverMs = 50;

  /**
   * 加满后的验证区域 — 截图与 /image/拉满.png 模板对比.
   * 匹配成功说明加满按钮点击已生效.
   */
  public int baitShopFillUpConfirmMarginLeft = 1131;
  public int baitShopFillUpConfirmMarginRight = 113;
  public int baitShopFillUpConfirmMarginTop = 621;
  public int baitShopFillUpConfirmMarginBottom = 74;
  public double baitShopFillUpConfirmThreshold = 0.70;

  /**
   * "购买" button in the shop (after 加满) — ROI margins (pixels from client edges).
   */
  public int baitShopPurchaseBtnMarginLeft = 900;
  public int baitShopPurchaseBtnMarginRight = 50;
  public int baitShopPurchaseBtnMarginTop = 655;
  public int baitShopPurchaseBtnMarginBottom = 17;

  /**
   * "确认" button on the purchase confirmation dialog — ROI margins (pixels from client edges).
   * Clicked after the purchase button to confirm the transaction.
   */
  public int baitShopConfirmBtnMarginLeft = 660;
  public int baitShopConfirmBtnMarginRight = 395;
  public int baitShopConfirmBtnMarginTop = 453;
  public int baitShopConfirmBtnMarginBottom = 230;

  /** Fraction of window height from bottom for the dismiss click (e.g. 0.20 = bottom 20%). */
  public double baitShopDismissBottomAreaPct = 0.20;

  /** Delay after UI actions for the game to render the transition (ms). */
  public int baitShopUiTransitionMs = 500;

  /** Key to open the bait selection wheel (default E). */
  public int baitShopEnterVk = java.awt.event.KeyEvent.VK_E;

  /** Key to exit the bait shop (default ESC). */
  public int baitShopEscVk = java.awt.event.KeyEvent.VK_ESCAPE;

  // --- Auto-sell flow ---

  /** Number of fish caught before auto-sell triggers. */
  public int sellThreshold = 100;

  /** Stop the bot after this many total fish (0 = never stop). */
  public int maxFishCount = 0;

  /** 货币不足弹窗 — 在商城中右键选中鱼饵后可能出现 */
  public int baitShopNoCurrencyMarginLeft = 554;
  public int baitShopNoCurrencyMarginRight = 557;
  public int baitShopNoCurrencyMarginTop = 335;
  public int baitShopNoCurrencyMarginBottom = 334;
  public double baitShopNoCurrencyThreshold = 0.70;

  /** BaitShopFlow 检测到货币不足时设此标志, FishBot 收到后触发售卖流程 */
  public volatile boolean needsSellBeforeBait;

  /** Sell icon in the warehouse — ROI margins (pixels from client edges). */
  public int sellIconMarginLeft = 65;
  public int sellIconMarginRight = 1140;
  public int sellIconMarginTop = 245;
  public int sellIconMarginBottom = 415;

  /** "一键售卖" button — ROI margins (1280x720). */
  public int sellAllBtnMarginLeft = 660;
  public int sellAllBtnMarginRight = 521;
  public int sellAllBtnMarginTop = 631;
  public int sellAllBtnMarginBottom = 63;

  /** "确认" button on the sell confirm dialog — ROI margins. */
  public int sellConfirmBtnMarginLeft = 670;
  public int sellConfirmBtnMarginRight = 384;
  public int sellConfirmBtnMarginTop = 452;
  public int sellConfirmBtnMarginBottom = 230;

  /** 出售确认弹窗验证 — 点击一键售卖后用 /image/一键出售确认弹窗.png 校验 */
  public int sellConfirmDialogMarginLeft = 581;
  public int sellConfirmDialogMarginRight = 583;
  public int sellConfirmDialogMarginTop = 226;
  public int sellConfirmDialogMarginBottom = 450;
  public double sellConfirmDialogThreshold = 0.70;

  /** 鱼饵未选择弹窗 — 点击"开始钓鱼"后如果没装鱼饵会弹出, 点击后打开轮盘 */
  public int baitNotSelectedMarginLeft = 1099;
  public int baitNotSelectedMarginRight = 78;
  public int baitNotSelectedMarginTop = 470;
  public int baitNotSelectedMarginBottom = 166;
  public double baitNotSelectedThreshold = 0.70;

  /** Pre-READY 界面检测 — 匹配 /image/开始钓鱼.png, 匹配到后点击该位置 */
  public int preReadyBtnMarginLeft = 841;
  public int preReadyBtnMarginRight = 27;
  public int preReadyBtnMarginTop = 595;
  public int preReadyBtnMarginBottom = 61;
  public double preReadyBtnThreshold = 0.70;

  // --- 月卡领取流程 ---

  /**
   * 月卡领取页面检测 — /image/月卡领取.png
   * 检测后按ESC进入月卡领取确认页面
   */
  public int monthlyCardClaimMarginLeft = 497;
  public int monthlyCardClaimMarginRight = 483;
  public int monthlyCardClaimMarginTop = 154;
  public int monthlyCardClaimMarginBottom = 52;
  public double monthlyCardClaimThreshold = 0.70;

  /**
   * 月卡领取确认页面检测 — /image/月卡领取确认.png
   * 检测后点击下方位置退出, 最多重试3次, 仍不退出则强制ESC
   */
  public int monthlyCardClaimConfirmMarginLeft = 555;
  public int monthlyCardClaimConfirmMarginRight = 555;
  public int monthlyCardClaimConfirmMarginTop = 345;
  public int monthlyCardClaimConfirmMarginBottom = 284;
  public double monthlyCardClaimConfirmThreshold = 0.70;

  /** 确认页点击退出重试次数 */
  public int monthlyCardConfirmMaxRetries = 3;

  /** 点击底部区域比例 — 确认页点击位置为 clientHeight * (1 - 此值/2) */
  public double monthlyCardDismissBottomAreaPct = 0.20;

  /** 检测窗口(UTC+8): 4:59 ~ 5:01 (游戏服务器5:00重置) */
  public int monthlyCardCheckHourStart = 4;
  public int monthlyCardCheckMinuteStart = 59;
  public int monthlyCardCheckHourEnd = 5;
  public int monthlyCardCheckMinuteEnd = 1;

  /** 月卡检测轮询间隔(ms) */
  public int monthlyCardPollIntervalMs = 5000;

  /** VK code: cast from ready (default F). */
  public int castVk = java.awt.event.KeyEvent.VK_F;

  /** Bite spam key (usually same as cast). */
  public int biteVk = java.awt.event.KeyEvent.VK_F;

  public int steerLeftVk = java.awt.event.KeyEvent.VK_A;
  public int steerRightVk = java.awt.event.KeyEvent.VK_D;

  // --- ROI as fraction of client [0,1] (left, top, right, bottom) ---

  /**
   * ROI for {@code FIGHTING状态判断.png} — the UI element that signals the fighting phase has
   * begun. Adjust if the indicator appears in a different region of the screen.
   */
  // ROI for FIGHTING状态判断.png: ~66×66, contains the 56×57 template with
  // ~5 px slack. Must stay strictly larger than the template.
  public double fightIndicatorRoiL = 0.248;  // 317 / 1280
  public double fightIndicatorRoiT = 0.024;  //  17 / 720
  public double fightIndicatorRoiR = 0.299;  // 383 / 1280
  public double fightIndicatorRoiB = 0.115;  //  83 / 720

  /** Slider band where yellow marker and green zone move. Margins measured from each edge. */
  public double fightRoiL = 0.319;   // 408 / 1280
  public double fightRoiT = 0.056;   //  40 / 720
  public double fightRoiR = 0.688;   // 880 / 1280 (= 1280-400)
  public double fightRoiB = 0.078;   //  56 / 720 (= 720-664)

  /** Success indicator — tight ROI around the success text/icon. Margins from each edge. */
  public double successRoiL = 0.442;   // 566 / 1280
  public double successRoiT = 0.889;   // 640 / 720
  public double successRoiR = 0.562;   // 719 / 1280 (= 1280-561)
  public double successRoiB = 0.926;   // 667 / 720 (= 720-53)
}
