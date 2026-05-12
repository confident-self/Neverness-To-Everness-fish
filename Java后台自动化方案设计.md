# Java 后台游戏自动化技术方案

## 目标

实现游戏窗口在后台（最小化或被遮挡）运行时仍然可以进行截图+键鼠操作，不影响用户正常使用其他程序。

## 核心技术：PostMessage + PrintWindow

绕过前台自动化的根本思路：不走全局输入通道，不走屏幕截图，直接用 Win32 的消息机制**与目标窗口私下通信**。

对比：

```
前台方案（错误方向）：
  Robot.createScreenCapture() → 截的是屏幕，窗口必须可见
  Robot.keyPress()            → 走的全局输入队列，焦点会被抢

后台方案（正确方向）：
  user32.PrintWindow()        → 命令窗口自己渲染到内存，不管可见性
  user32.PostMessage()        → 消息直接投入目标窗口队列，不管焦点
```

## 依赖

```xml
<!-- JNA: 调用 Win32 API -->
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna-platform</artifactId>
    <version>5.14.0</version>
</dependency>

<!-- OpenCV: 模板匹配 -->
<dependency>
    <groupId>org.openpnp</groupId>
    <artifactId>opencv</artifactId>
    <version>4.9.0-0</version>
</dependency>
```

## 模块一：后台截图（PrintWindow）

**原理：** `PrintWindow` 给目标窗口发 `WM_PRINT` 消息，窗口会把自己的内容绘制到你提供的 GDI DC 上。跟窗口是否最小化、是否被遮挡完全无关。

第三个参数 `PW_RENDERFULLCONTENT = 3` 确保 DirectX 渲染的内容也能被捕获。

```java
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.ptr.IntByReference;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

public class BackgroundCapture {

    private static final int PW_RENDERFULLCONTENT = 3;

    /**
     * 后台截取指定窗口的完整画面，返回 PNG 字节数组。
     * @param hwnd    目标窗口句柄
     * @param rect    窗口内的截图区域，传 null 则用窗口客户区大小
     */
    public static byte[] captureToPng(HWND hwnd, RECT rect) {
        if (rect == null) {
            rect = new RECT();
            User32.INSTANCE.GetClientRect(hwnd, rect);  // 获取窗口客户区尺寸
        }

        int width  = rect.right - rect.left;
        int height = rect.bottom - rect.top;

        HDC windowDC = User32.INSTANCE.GetWindowDC(hwnd);         // 1. 获取窗口 DC
        HDC memDC   = GDI32.INSTANCE.CreateCompatibleDC(windowDC);// 2. 创建兼容 DC（内存画布）

        // 3. 创建兼容位图作为渲染目标
        HBITMAP bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(windowDC, width, height);
        HGDIOBJ oldObj = GDI32.INSTANCE.SelectObject(memDC, bitmap);

        // 4. 核心：命令窗口绘制到我们的内存 DC
        boolean success = User32Extra.INSTANCE.PrintWindow(hwnd, memDC, PW_RENDERFULLCONTENT);

        byte[] result = null;
        if (success) {
            // 5. 从位图提取像素数据
            byte[] bmpData = new byte[width * height * 4]; // 32位色深，每个像素4字节 BGRA
            GDI32.INSTANCE.GetBitmapBits(bitmap, bmpData.length, bmpData);

            // 6. 转换为 BufferedImage → PNG
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int b = bmpData[offset]     & 0xFF;
                    int g = bmpData[offset + 1] & 0xFF;
                    int r = bmpData[offset + 2] & 0xFF;
                    image.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            result = baos.toByteArray();
        }

        // 7. 释放 GDI 资源
        GDI32.INSTANCE.SelectObject(memDC, oldObj);
        GDI32.INSTANCE.DeleteObject(bitmap);
        GDI32.INSTANCE.DeleteDC(memDC);
        User32.INSTANCE.ReleaseDC(hwnd, windowDC);

        return result;
    }
}

// PrintWindow 的 JNA 映射（User32 标准绑定里没有这个方法）
interface User32Extra extends com.sun.jna.Library {
    User32Extra INSTANCE = com.sun.jna.Native.load("user32", User32Extra.class);

    boolean PrintWindow(HWND hwnd, HDC hdcBlt, int nFlags);
}
```

## 模块二：后台键鼠（PostMessage）

**原理：** `PostMessage` 直接把 `WM_KEYDOWN` / `WM_KEYUP` / `WM_LBUTTONDOWN` 等消息投递到目标窗口的消息队列中，绕过系统的全局输入队列。系统不知道有任何按键发生，当前焦点程序不受任何干扰。

```java
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.User32;

public class BackgroundInput {

    // ==================== 键盘 ====================

    /** 发送按键按下消息到目标窗口 */
    public static void keyDown(HWND hwnd, int vkCode) {
        User32.INSTANCE.PostMessage(
            hwnd,
            WinUser.WM_KEYDOWN,
            new WPARAM(vkCode),
            new LPARAM(0)
        );
    }

    /** 发送按键弹起消息到目标窗口 */
    public static void keyUp(HWND hwnd, int vkCode) {
        User32.INSTANCE.PostMessage(
            hwnd,
            WinUser.WM_KEYUP,
            new WPARAM(vkCode),
            new LPARAM(0x00000001 | (1 << 30) | (1 << 31))
            // LPARAM bit 0-15: 重复计数1
            // bit 30: 之前按键状态（按下过）
            // bit 31: 释放状态
        );
    }

    /** 按键按下并保持指定时长后弹起 */
    public static void keyPress(HWND hwnd, int vkCode, long durationMs) {
        keyDown(hwnd, vkCode);
        sleep(durationMs);
        keyUp(hwnd, vkCode);
    }

    // ==================== 鼠标 ====================

    /** 静态方法：构造鼠标坐标的 LPARAM */
    private static LPARAM makeLParam(int x, int y) {
        return new LPARAM((y << 16) | (x & 0xFFFF));
    }

    /** 发送鼠标左键按下 */
    public static void mouseLeftDown(HWND hwnd, int x, int y) {
        User32.INSTANCE.PostMessage(
            hwnd,
            WinUser.WM_LBUTTONDOWN,
            new WPARAM(WinUser.MK_LBUTTON),
            makeLParam(x, y)
        );
    }

    /** 发送鼠标左键弹起 */
    public static void mouseLeftUp(HWND hwnd, int x, int y) {
        User32.INSTANCE.PostMessage(
            hwnd,
            WinUser.WM_LBUTTONUP,
            new WPARAM(0),
            makeLParam(x, y)
        );
    }

    /** 发送鼠标左键点击（按下+弹起） */
    public static void mouseLeftClick(HWND hwnd, int x, int y, long durationMs) {
        mouseLeftDown(hwnd, x, y);
        sleep(durationMs);
        mouseLeftUp(hwnd, x, y);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

### 常用虚拟键码速查

```java
public class VK {
    public static final int A = 0x41, B = 0x42, C = 0x43, D = 0x44, E = 0x45;
    public static final int F = 0x46, G = 0x47, Q = 0x51, R = 0x52, W = 0x57;
    public static final int ESC    = 0x1B;
    public static final int SPACE  = 0x20;
    public static final int SHIFT  = 0x10;
    public static final int CTRL   = 0x11;
    public static final int LSHIFT = 0xA0;
    public static final int LCTRL  = 0xA2;
}
```

## 模块三：窗口查找

```java
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.User32;

public class WindowFinder {

    /**
     * 按窗口类名和标题精确查找
     * @param className 窗口类名，如 "UnrealWindow"；传 null 不限制
     * @param title     窗口标题，如 "异环  "；传 null 不限制
     */
    public static HWND findByClassAndTitle(String className, String title) {
        return User32.INSTANCE.FindWindow(className, title);
    }

    /** 获取窗口客户区坐标（相对于屏幕） */
    public static WinDef.RECT getClientRectOnScreen(HWND hwnd) {
        WinDef.RECT rect = new WinDef.RECT();
        User32.INSTANCE.GetClientRect(hwnd, rect);         // 客户区相对于自身的坐标
        WinDef.POINT pt = new WinDef.POINT(0, 0);
        User32.INSTANCE.ClientToScreen(hwnd, pt);          // 转为屏幕坐标
        // 结果：窗口左上角在屏幕的 (pt.x, pt.y)，客户区宽高 = rect
        return rect;
    }
}
```

## 模块四：窗口假激活（持续欺骗游戏）

很多游戏检测到自己处于非激活状态时会暂停或拒绝处理输入。需要定时发送 `WM_ACTIVATE` 让游戏以为自己是前台窗口。

```java
public class KeepAlive {

    private static final int WM_ACTIVATE = 0x0006;
    private static final int WA_ACTIVE  = 1;

    /**
     * 启动一个后台线程，每 3 秒发一次 WM_ACTIVATE。
     * 调用方通过 stopFlag 控制退出。
     */
    public static Thread startKeepAliveThread(HWND hwnd, AtomicBoolean stopFlag) {
        Thread t = new Thread(() -> {
            while (stopFlag.get()) {
                User32.INSTANCE.SendMessage(
                    hwnd,
                    WM_ACTIVATE,
                    new WPARAM(WA_ACTIVE),
                    new LPARAM(0)
                );
                try {
                    for (int i = 0; i < 6 && stopFlag.get(); i++) {
                        Thread.sleep(500); // 每 0.5 秒检查一次停止标记
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "window-keep-alive");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
```

## 模块五：OpenCV 模板匹配（使用上面截图的 PNG 数据）

```java
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Core.MinMaxLocResult;

import java.io.ByteArrayInputStream;

public class TemplateMatcher {

    static { System.loadLibrary(Core.NATIVE_LIBRARY_NAME); }

    /**
     * 在截图数据的指定区域内做模板匹配。
     * @param pngData      BackgroundCapture 返回的 PNG 字节数组
     * @param roi          限定搜索区域 (x, y, width, height) — 相对于截图的坐标
     * @param templatePath 模板图片文件路径
     * @param threshold    最低相似度阈值 (0.0 ~ 1.0)
     * @return MatchResult: (matched, maxVal, maxX, maxY)
     */
    public static MatchResult match(byte[] pngData, Rect roi, String templatePath, double threshold) {
        // 1. 从内存解码背景图
        Mat scene = Imgcodecs.imdecode(
            new MatOfByte(pngData),
            Imgcodecs.IMREAD_COLOR
        );

        // 2. 加载模板
        Mat template = Imgcodecs.imread(templatePath);

        // 3. 裁剪限定区域
        Mat roiMat = scene.submat(
            new Range(roi.y, roi.y + roi.height),
            new Range(roi.x, roi.x + roi.width)
        );

        // 4. 模板匹配
        Mat result = new Mat();
        Imgproc.matchTemplate(roiMat, template, result, Imgproc.TM_CCOEFF_NORMED);

        // 5. 找最佳匹配点
        MinMaxLocResult mmr = Core.minMaxLoc(result);
        double maxVal = mmr.maxVal;

        if (maxVal >= threshold) {
            int matchX = (int) mmr.maxLoc.x + roi.x;  // 转换回原图坐标
            int matchY = (int) mmr.maxLoc.y + roi.y;
            return new MatchResult(true, maxVal, matchX, matchY);
        }

        return new MatchResult(false, maxVal, -1, -1);
    }

    /**
     * 模板匹配的变体：返回所有高于阈值的匹配点的包围矩形。
     * 钓鱼需要用这个——先找到"判定区域"的左右边界，再判断滑块是否在区间内。
     */
    public static BoundingRectResult matchWithBounds(
            byte[] pngData, Rect roi, String templatePath, double threshold) {

        Mat scene = Imgcodecs.imdecode(new MatOfByte(pngData), Imgcodecs.IMREAD_COLOR);
        Mat template = Imgcodecs.imread(templatePath);
        Mat roiMat = scene.submat(
            new Range(roi.y, roi.y + roi.height),
            new Range(roi.x, roi.x + roi.width)
        );

        Mat result = new Mat();
        Imgproc.matchTemplate(roiMat, template, result, Imgproc.TM_CCOEFF_NORMED);

        // 收集所有 >= threshold 的位置
        java.util.List<Point> matches = new java.util.ArrayList<>();
        for (int y = 0; y < result.rows(); y++) {
            for (int x = 0; x < result.cols(); x++) {
                if (result.get(y, x)[0] >= threshold) {
                    matches.add(new Point(x, y));
                }
            }
        }

        if (matches.isEmpty()) {
            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);
            return new BoundingRectResult(false, mmr.maxVal, 0, 0, 0, 0);
        }

        double maxVal = Core.minMaxLoc(result).maxVal;
        int minX = (int) matches.stream().mapToDouble(p -> p.x).min().getAsDouble() + roi.x;
        int minY = (int) matches.stream().mapToDouble(p -> p.y).min().getAsDouble() + roi.y;
        int maxX = (int) matches.stream().mapToDouble(p -> p.x).max().getAsDouble() + roi.x + template.cols();
        int maxY = (int) matches.stream().mapToDouble(p -> p.y).max().getAsDouble() + roi.y + template.rows();

        return new BoundingRectResult(true, maxVal, minX, minY, maxX, maxY);
    }

    // 结果 DTO
    public record MatchResult(boolean matched, double maxVal, int x, int y) {}
    public record BoundingRectResult(boolean matched, double maxVal,
                                      int minX, int minY, int maxX, int maxY) {}
}
```

## 完整钓鱼流程组装示例

```java
public class AutoFishTask {

    private final HWND hwnd;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 游戏内的坐标区域（从 Python 原版移植）
    private static final Rect 判定区域_ROI    = new Rect(391, 33,  503 - 391, 43 - 33);
    private static final Rect 钓鱼成功判定_ROI = new Rect(301, 288, 562 - 301, 427 - 288);

    private static final int KEY_A = 0x41, KEY_D = 0x44, KEY_F = 0x46, KEY_ESC = 0x1B;

    public void start() {
        running.set(true);
        Thread keepAlive = KeepAlive.startKeepAliveThread(hwnd, running);

        long startTime = System.currentTimeMillis();
        while (running.get() && System.currentTimeMillis() - startTime < 60_000) {

            // 1. 后台截图
            byte[] frame = BackgroundCapture.captureToPng(hwnd, null);
            if (frame == null) continue;

            // 2. 检测鱼耐力是否耗尽
            MatchResult nailCheck = TemplateMatcher.match(
                frame, 钓鱼成功判定_ROI, "钓鱼成功判定图片.png", 0.8);
            if (nailCheck.matched()) {
                System.out.println("鱼耐力耗尽，结束");
                break;
            }

            // 3. 按 F 键（钓鱼/收线）
            BackgroundInput.keyPress(hwnd, KEY_F, 10);

            // 4. 找到判定区域的左右边界（用灰度图模板）
            BoundingRectResult zone = TemplateMatcher.matchWithBounds(
                frame, 判定区域_ROI, "判定区域.png", 0.9);

            // 5. 找到滑块的 X 坐标
            MatchResult slider = TemplateMatcher.match(
                frame, 判定区域_ROI, "滑块.png", 0.8);

            // 6. 比较滑块与判定区域的位置，移动 A/D
            if (zone.matched() && slider.matched()) {
                if (zone.minX() < slider.x() && zone.maxX() > slider.x()) {
                    // 滑块在判定区域内 → 停止
                    BackgroundInput.keyUp(hwnd, KEY_D);
                    BackgroundInput.keyUp(hwnd, KEY_A);
                } else if (zone.minX() > slider.x()) {
                    // 滑块偏左 → 按 D 向右
                    BackgroundInput.keyUp(hwnd, KEY_A);
                    BackgroundInput.keyDown(hwnd, KEY_D);
                } else {
                    // 滑块偏右 → 按 A 向左
                    BackgroundInput.keyUp(hwnd, KEY_D);
                    BackgroundInput.keyDown(hwnd, KEY_A);
                }
            }

            sleep(5);
        }

        // 清理
        BackgroundInput.keyUp(hwnd, KEY_D);
        BackgroundInput.keyUp(hwnd, KEY_A);
        running.set(false);
        keepAlive.interrupt();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { running.set(false); }
    }
}
```

## 关键注意事项

1. **管理员权限**：`PrintWindow` 需要管理员权限才能获取 DirectX 窗口内容。启动时必须 `run as admin`。

2. **PrintWindow 兼容性**：Unreal Engine 游戏通常支持。如果截出来是黑屏，说明游戏没有维护 GDI 兼容表面，需要降级为前台 BitBlt 方案。

3. **PostMessage 的 LPARAM**：键盘消息的 LPARAM 不是随便传 0 就行。有些游戏会校验 LPARAM 中的扫描码、前次按键状态、重复计数等字段。如果按键无效，可以参考原 Python 项目的 LPARAM 值。上面的 `keyUp` 示例已经带了正确的 bit 标记。

4. **鼠标坐标**：`WM_LBUTTONDOWN` 的坐标是**窗口客户区相对坐标**，不是屏幕坐标。但某些游戏（如 NTE）需要真实鼠标位置配合。如果是这种情况，需要额外调用：
   ```java
   WinUser.SystemParametersInfo(...) // 移动真实光标
   ```

5. **线程安全**：`PostMessage` 是线程安全的，可以从任意线程调用。`PrintWindow` 最好保持在单线程中使用，避免 GDI 资源竞争。
