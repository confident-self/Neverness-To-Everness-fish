package com.yihuan.fish;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.yihuan.fish.input.BotInput;
import com.yihuan.fish.input.KeepAlive;
import com.yihuan.fish.input.WinConsole;
import com.yihuan.fish.vision.TemplateBank;
import com.yihuan.fish.win.GameWindow;
import com.yihuan.fish.win.WinUser32;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.image.BufferedImage;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;

public final class FishBotUI {
  private static final int VK_F12 = 0x7B;

  private final FishConfig cfg = new FishConfig();
  private final JFrame frame;
  private final JLabel statusLabel;
  private final JLabel fishCountLabel;
  private final JTextArea logArea;
  private final JButton startBtn;
  private final JButton stopBtn;
  private final JSpinner sellThresholdSpinner;
  private final JSpinner maxFishSpinner;
  private final JButton themeBtn;

  private boolean darkTheme = true;
  private volatile FishBot bot;
  private volatile boolean running;
  private AWTEventListener fKeyFilter;

  public FishBotUI() {
    FlatDarkLaf.setup();

    frame = new JFrame("NToE-Fish - 异环自动钓鱼");
    frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    frame.setSize(700, 560);
    frame.setIconImage(createAppIcon());
    frame.setLocationRelativeTo(null);
    frame.setLayout(new BorderLayout(8, 8));

    // ── Status bar ──
    JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    themeBtn = new JButton(darkTheme ? "☀" : "🌙");
    themeBtn.setFont(themeBtn.getFont().deriveFont(13f));
    themeBtn.setFocusable(false);
    themeBtn.setMargin(new Insets(0, 4, 0, 4));
    themeBtn.addActionListener(e -> toggleTheme());

    statusLabel = new JLabel("● 停止");
    statusLabel.setForeground(Color.RED);
    statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
    fishCountLabel = new JLabel("🎣 钓鱼: 0  周期: 0");
    statusPanel.add(themeBtn);
    statusPanel.add(statusLabel);
    statusPanel.add(fishCountLabel);
    frame.add(statusPanel, BorderLayout.NORTH);

    // ── Control panel ──
    JPanel ctrl = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(4, 6, 4, 6);
    gbc.fill = GridBagConstraints.HORIZONTAL;

    startBtn = new JButton("▶ 启动任务");
    stopBtn = new JButton("⏹ 停止任务");
    stopBtn.setEnabled(false);

    startBtn.addActionListener(e -> startBot());
    stopBtn.addActionListener(e -> stopBot());

    sellThresholdSpinner = new JSpinner(new SpinnerNumberModel(cfg.sellThreshold, 0, 9999, 1));
    maxFishSpinner = new JSpinner(new SpinnerNumberModel(cfg.maxFishCount, 0, 99999, 1));

    // Row 0: buttons
    gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1;
    ctrl.add(startBtn, gbc);
    gbc.gridx = 1;
    ctrl.add(stopBtn, gbc);
    gbc.gridx = 2; gbc.weightx = 1;
    ctrl.add(new JLabel(), gbc);

    // Row 1: sell threshold
    gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
    ctrl.add(new JLabel("售卖阈值 (条):"), gbc);
    gbc.gridx = 1; gbc.gridwidth = 1;
    ctrl.add(sellThresholdSpinner, gbc);
    gbc.gridx = 2; gbc.weightx = 1;
    ctrl.add(new JLabel("到达条数后自动售卖"), gbc);

    // Row 2: max fish
    gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
    ctrl.add(new JLabel("结束条件 (条):"), gbc);
    gbc.gridx = 1; gbc.gridwidth = 1;
    ctrl.add(maxFishSpinner, gbc);
    gbc.gridx = 2; gbc.weightx = 1;
    ctrl.add(new JLabel("0 = 永不停止"), gbc);

    frame.add(ctrl, BorderLayout.CENTER);

    // ── Log area ──
    logArea = new JTextArea(12, 50);
    logArea.setEditable(false);
    logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    applyLogColors();
    ((DefaultCaret) logArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
    JScrollPane scroll = new JScrollPane(logArea);
    scroll.setBorder(BorderFactory.createTitledBorder("运行日志"));
    frame.add(scroll, BorderLayout.SOUTH);

    // ── Log handler ──
    setupUILogging();

    // ── Status update timer (1s) ──
    new Timer(1000, e -> updateStatus()).start();

    // ── F12 stop poller ──
    Thread f12 = new Thread(() -> {
      while (true) {
        if (running && (WinUser32.INSTANCE.GetAsyncKeyState(VK_F12) & 0x8000) != 0) {
          SwingUtilities.invokeLater(this::stopBot);
        }
        try { Thread.sleep(120); } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }, "f12-poller");
    f12.setDaemon(true);
    f12.start();

    // ── Filter F key from reaching UI components ──
    installFKeyFilter();
  }

  /** 生成一个简单的应用图标（蓝色圆形+白色F） */
  private static Image createAppIcon() {
    int s = 64;
    BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    // 蓝色圆形背景
    g.setColor(new Color(41, 128, 185));
    g.fillOval(1, 1, s - 2, s - 2);
    g.setColor(new Color(52, 152, 219));
    g.fillOval(6, 6, s - 12, s - 12);
    // 白色字母 F
    g.setColor(Color.WHITE);
    g.setFont(new Font("Arial", Font.BOLD, 36));
    FontMetrics fm = g.getFontMetrics();
    String f = "F";
    int fx = (s - fm.stringWidth(f)) / 2;
    int fy = (s + fm.getAscent()) / 2 - 2;
    g.drawString(f, fx, fy);
    g.dispose();
    return img;
  }

  // ══════════════════════════════════════════
  //  Theme toggle
  // ══════════════════════════════════════════

  private void toggleTheme() {
    darkTheme = !darkTheme;
    if (darkTheme) {
      FlatDarkLaf.setup();
      themeBtn.setText("☀");
    } else {
      FlatLightLaf.setup();
      themeBtn.setText("🌙");
    }
    applyLogColors();
    SwingUtilities.updateComponentTreeUI(frame);
  }

  private void applyLogColors() {
    if (darkTheme) {
      logArea.setBackground(new Color(30, 30, 30));
      logArea.setForeground(new Color(200, 200, 200));
    } else {
      logArea.setBackground(new Color(245, 245, 245));
      logArea.setForeground(new Color(30, 30, 30));
    }
  }

  // ══════════════════════════════════════════
  //  Filter F key from bot spamming into UI
  // ══════════════════════════════════════════

  private void installFKeyFilter() {
    fKeyFilter = event -> {
      if (!running) return;
      if (event instanceof KeyEvent ke
          && ke.getID() == KeyEvent.KEY_PRESSED
          && ke.getKeyCode() == KeyEvent.VK_F) {
        ke.consume();
      }
    };
    Toolkit.getDefaultToolkit().addAWTEventListener(fKeyFilter, AWTEvent.KEY_EVENT_MASK);
  }

  // ══════════════════════════════════════════

  public void show() {
    frame.setVisible(true);
  }

  // ── Bot lifecycle ──

  private void startBot() {
    if (running) return;

    cfg.sellThreshold = (int) sellThresholdSpinner.getValue();
    cfg.maxFishCount = (int) maxFishSpinner.getValue();

    startBtn.setEnabled(false);
    stopBtn.setEnabled(true);
    sellThresholdSpinner.setEnabled(false);
    maxFishSpinner.setEnabled(false);
    logArea.setText("");
    statusLabel.setText("● 启动中...");
    statusLabel.setForeground(new Color(0xFFA500));

    running = true;

    Thread botThread = new Thread(() -> {
      try {
        runBotLoop();
      } catch (Exception ex) {
        LOG.log(Level.SEVERE, "Bot crashed", ex);
        appendLog("❌ 异常崩溃: " + ex.getMessage());
      } finally {
        running = false;
        SwingUtilities.invokeLater(() -> {
          statusLabel.setText("● 已停止");
          statusLabel.setForeground(Color.RED);
          startBtn.setEnabled(true);
          stopBtn.setEnabled(false);
          sellThresholdSpinner.setEnabled(true);
          maxFishSpinner.setEnabled(true);
        });
      }
    }, "bot-worker");
    botThread.setDaemon(true);
    botThread.start();
  }

  private void stopBot() {
    if (bot != null) {
      bot.requestStop();
    }
  }

  private void runBotLoop() throws Exception {
    WinConsole.disableQuickEdit();
    TemplateBank bank = new TemplateBank(cfg.imageDir);
    GameWindow window = new GameWindow(cfg.windowTitleSubstring);
    BotInput input = new BotInput(window, cfg);
    PhaseDetector detector = new PhaseDetector(cfg, bank);
    PhaseStabilizer stabilizer = new PhaseStabilizer(cfg.phaseStableFrames);
    BaitShopFlow baitShop = new BaitShopFlow(cfg, bank, input);
    AutoSellFlow autoSell = new AutoSellFlow(cfg, input, bank);
    bot = new FishBot(cfg, window, input, detector, stabilizer, baitShop, autoSell, bank);

    SwingUtilities.invokeLater(() -> {
      statusLabel.setText("● 运行中");
      statusLabel.setForeground(new Color(0x00CC66));
    });

    AtomicBoolean keepAliveRunning = new AtomicBoolean(true);
    window.refresh();
    if (window.isValid() && cfg.postMessageKeys) {
      KeepAlive.start(window.getHwnd(), keepAliveRunning);
    }

    try {
      bot.run();
    } finally {
      keepAliveRunning.set(false);
      bot = null;
    }
  }

  // ── Status update ──

  private void updateStatus() {
    FishBot b = bot;
    if (b != null) {
      fishCountLabel.setText("🎣 钓鱼: " + b.getTotalFishCaught() + "  周期: " + b.getFishCaughtCount());
    }
  }

  // ── Logging ──

  private static final Logger LOG = Logger.getLogger(FishBotUI.class.getName());

  private void setupUILogging() {
    Logger root = Logger.getLogger("");
    root.setLevel(Level.INFO);

    for (Handler h : root.getHandlers()) {
      root.removeHandler(h);
    }

    UILogHandler uiHandler = new UILogHandler();
    uiHandler.setLevel(Level.INFO);
    root.addHandler(uiHandler);
  }

  private void appendLog(String msg) {
    SwingUtilities.invokeLater(() -> {
      logArea.append(msg + "\n");
      if (logArea.getLineCount() > 2000) {
        try {
          int end = logArea.getLineEndOffset(logArea.getLineCount() - 1000);
          logArea.replaceRange("", 0, end);
        } catch (Exception ignored) {}
      }
    });
  }

  private class UILogHandler extends Handler {
    private final SimpleFormatter fmt = new SimpleFormatter();

    UILogHandler() { setFormatter(fmt); }

    @Override
    public void publish(LogRecord record) {
      if (!isLoggable(record)) return;
      appendLog(fmt.format(record).stripTrailing());
    }

    @Override public void flush() {}
    @Override public void close() {}
  }

  // ── Entry point ──

  public static void main(String[] args) {
    System.setProperty("sun.java2d.dpiaware", "true");
    SwingUtilities.invokeLater(() -> new FishBotUI().show());
  }
}
