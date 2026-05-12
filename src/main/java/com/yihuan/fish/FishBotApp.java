package com.yihuan.fish;

import javax.swing.*;

/**
 * Entry point. Launches the Swing UI which manages the bot lifecycle.
 */
public final class FishBotApp {
  public static void main(String[] args) {
    System.setProperty("sun.java2d.dpiaware", "true");
    SwingUtilities.invokeLater(() -> new FishBotUI().show());
  }
}
