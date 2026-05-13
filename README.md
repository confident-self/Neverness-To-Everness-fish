# NToE-Fish — 异环自动钓鱼

异环游戏自动钓鱼机器人。Java 17 + Maven，后台截图 + PostMessage 键鼠，不占用前台操作。

## 功能

- **自动钓鱼** — 抛竿 → 搏鱼（滑块追踪） → 收鱼，全自动闭环
- **自动购买鱼饵** — 检测无料提示 → 进轮盘选饵 → 商城购买 → 装备
- **自动售卖** — 达到阈值后进仓库 → 一键出售 → 确认
- **卡死恢复** — 点击验证 + 自动重试 + ESC 脱离
- **月卡弹窗** — 每日 5:00 自动检测并关闭
- **完全后台运行** — 游戏最小化/被遮挡时正常工作

## 环境要求

- Windows 10 / Windows 11
- 游戏窗口 1280 × 720，帧率限制 30 FPS

## 快速开始

### 方式一：下载 exe 运行

1. 在 [Releases](https://github.com/confident-self/Neverness-To-Everness-fish/releases) 下载最新版
2. 解压到任意目录
3. 右键 `NToE-Fish.exe` → **以管理员身份运行**
4. 进入游戏钓鱼界面，点击 UI 中的 **▶ 启动任务**

### 方式二：源码编译运行

```bash
# 编译
mvn package

# 运行
mvn exec:java
```

### 打包 exe

```bash
bash scripts/build-exe.sh
# 生成到 output/NToE-Fish/
```

## 界面说明

![UI 界面](https://via.placeholder.com/700x560/1e1e1e/cccccc?text=NToE-Fish+UI)

- **状态栏** — 当前运行状态、钓鱼计数
- **控制面板** — 启动/停止、售卖阈值、结束条件
- **日志面板** — 实时输出运行日志

## 配置参数

| 参数 | 默认 | 说明 |
|------|------|------|
| 售卖阈值 | 100 | 钓鱼条数达到此值后自动售卖 |
| 结束条件 | 0 | 总钓鱼条数达到后停止（0=永不停止）|

## 停止方式

- 点击 UI **⏹ 停止任务** 按钮
- 长按 **F12** 键

## 注意

1. **必须使用管理员权限运行**（PrintWindow + PostMessage 需要）
2. 游戏窗口标题需包含"异环"二字
3. 游戏窗口不要最小化（可被遮挡）
4. 运行期间可正常使用其他程序

## 技术栈

- Java 21 + Maven
- JNA (Win32 API: PostMessage, SendInput, PrintWindow)
- OpenCV (JavaCPP) NCC 模板匹配
- FlatLaf (Swing UI 主题)
- jpackage (exe 打包)

## 许可证

MIT
