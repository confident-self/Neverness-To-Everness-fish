package com.yihuan.fish;

import com.yihuan.fish.input.BotInput;
import com.yihuan.fish.input.KeepAlive;
import com.yihuan.fish.input.WinConsole;
import com.yihuan.fish.vision.TemplateBank;
import com.yihuan.fish.win.GameWindow;
import com.yihuan.fish.win.WinUser32;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class FishBotApp {
  private static final Logger LOG = Logger.getLogger(FishBotApp.class.getName());
  private static final int VK_F12 = 0x7B;

  public static void main(String[] args) throws Exception {
    // Must be set before any AWT/Robot usage — aligns Java2D coordinates with physical screen
    // pixels on high-DPI displays. Without this, Robot.createScreenCapture() can capture the
    // wrong screen region because the JVM uses logical coordinates while Win32 uses physical ones.
    System.setProperty("sun.java2d.dpiaware", "true");

    setupLogging();
    WinConsole.disableQuickEdit();

    FishConfig cfg = new FishConfig();
    TemplateBank bank = new TemplateBank(cfg.imageDir);
    GameWindow window = new GameWindow(cfg.windowTitleSubstring);
    BotInput input = new BotInput(window, cfg);
    if (cfg.postMessageKeys) {
      LOG.info(
          "Keyboard: PostMessage (background) scanCode="
              + cfg.winSendInputUseScanCode);
    } else if (cfg.winSendInputKeys) {
      LOG.info(
          "Keyboard: Win32 SendInput scanCode="
              + cfg.winSendInputUseScanCode
              + " attachThread="
              + cfg.winUseAttachThreadInputForFocus
              + " mouseSendInput="
              + cfg.winSendInputMouse
              + ". Set winSendInputKeys=false for Robot.");
    } else {
      LOG.info("Keyboard: java.awt.Robot");
    }
    PhaseDetector detector = new PhaseDetector(cfg, bank);
    PhaseStabilizer stabilizer = new PhaseStabilizer(cfg.phaseStableFrames);
    BaitShopFlow baitShop = new BaitShopFlow(cfg, bank, input);
    AutoSellFlow autoSell = new AutoSellFlow(cfg, input);
    FishBot bot = new FishBot(cfg, window, input, detector, stabilizer, baitShop, autoSell);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  bot.requestStop();
                  LOG.info("JVM shutdown hook: stop requested (exit or Ctrl+C).");
                },
                "yihuan-fish-shutdown"));

    Thread stopPoller =
        new Thread(
            () -> {
              while (!bot.isStopRequested()) {
                if ((WinUser32.INSTANCE.GetAsyncKeyState(VK_F12) & 0x8000) != 0) {
                  bot.requestStop();
                  break;
                }
                try {
                  Thread.sleep(120);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            },
            "f12-stop-poller");
    stopPoller.setDaemon(true);
    stopPoller.start();

    LOG.info("Stop hotkey: hold F12 (polled ~120ms).");

    AtomicBoolean keepAliveRunning = new AtomicBoolean(true);
    window.refresh();
    if (window.isValid() && cfg.postMessageKeys) {
      KeepAlive.start(window.getHwnd(), keepAliveRunning);
      LOG.info("KeepAlive started (WM_ACTIVATE every 3s).");
    }

    try {
      bot.run();
    } finally {
      keepAliveRunning.set(false);
    }
  }

  private static void setupLogging() {
    LogManager.getLogManager().reset();
    Logger root = Logger.getLogger("");
    ConsoleHandler console = new ConsoleHandler();
    console.setLevel(Level.INFO);
    console.setFormatter(new SimpleFormatter());
    root.addHandler(console);
    root.setLevel(Level.INFO);
  }
}
