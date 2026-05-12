# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

- **Build**: `mvn package` (compiles Java 17, copies JNA deps to `target/lib/`, creates executable JAR)
- **Run**: `mvn exec:java` or `java -jar target/yihuan-fish-1.0.0-SNAPSHOT.jar`
- **Generate placeholder templates**: `powershell -File scripts/make-placeholders.ps1` (creates solid-color 32x32 PNGs in `image/` for testing)

Stop the bot by holding **F12** or sending Ctrl+C.

## Architecture

This is a fishing-minigame automation bot for an "异环" game on Windows. It screen-captures the game client area, detects game phase via NCC template matching, and sends keyboard/mouse input using both `java.awt.Robot` and Win32 `SendInput` (JNA). All parameters live in `FishConfig` — thresholds, timings, ROIs, and key mappings.

### Entry & Wiring

`FishBotApp` wires the object graph: `FishConfig` → `TemplateBank` (loads 3 PNGs from `image/`), `GameWindow` (finds the game HWND by title substring), `BotInput` (keyboard/mouse abstraction), `PhaseDetector` + `PhaseStabilizer` → `FishBot`. It also spawns a daemon F12 poller thread and registers a JVM shutdown hook.

### Core Loop (`FishBot.tick()`)

1. Locate game window and verify it's in the foreground
2. Screenshot the client area via `Robot.createScreenCapture()`
3. `PhaseDetector.detect()` → raw phase; `PhaseStabilizer.update()` → stable phase (requires N consecutive matching raw frames)
4. Dispatch action by stable phase (`READY`/`WAITING_BITE`/`FIGHTING`/`SUCCESS`/`IDLE`)

### Phase Detection (`PhaseDetector`)

Template matching order matters: **SUCCESS** screen checked first, then **FIGHTING** (yellow marker + green zone both must exceed threshold). If neither template matches and the previous tick was SUCCESS or FIGHTING, `PrepPhaseTracker` is updated to infer READY vs WAITING_BITE. `fightTemplateReleaseFrames` holds the FIGHTING state for a few frames when templates briefly dip below threshold, reducing oscillation.

### PrepPhaseTracker (state machine, no visual templates)

Distinguishes READY (ready to cast hook) from WAITING_BITE (hook in water, waiting for bite) purely by sequencing: SUCCESS exit → READY; cast key tap → WAITING_BITE; fight-exit without success → WAITING_BITE.

### Input (`input/` package)

`BotInput` is the sole input facade. By default (`winSendInputKeys=true`), keyboard uses Win32 `SendInput` with scan codes — many games ignore `java.awt.Robot` key events. Mouse clicks can also route through `SendInput` with absolute virtual-desktop coordinates (`winSendInputMouse=true`). Before each input, `activateForKeyboardInput()` uses `AttachThreadInput` to reliably steal foreground focus.

### Window (`win/` package)

`GameWindow` enumerates visible windows, matches by title substring ("异环"), and provides client-area screen coordinates. `WinUser32` is a small JNA interface for `ClientToScreen` and `GetAsyncKeyState` (F12 polling).

### Vision (`vision/` package)

`TemplateMatcher.matchBest()` performs brute-force NCC on the luminance channel within a configurable ROI fraction of the client area. `TemplateBank` loads `yellow_marker.png`, `green_zone.png`, and `success_screen.png` from the `image/` directory.

## Key Config Points

- `matchThreshold` (0.82): raise if false positives, lower if misses
- `phaseStableFrames`: increase if the bot reacts to flickering detections; lower if it feels sluggish
- `fightTemplateReleaseFrames`: tolerance for brief template dropouts during FIGHTING
- `winSendInputKeys` + `winSendInputUseScanCode`: switch input method if the game ignores keystrokes
- ROI values (`fightRoi*`, `successRoi*`) are fractions [0,1] of client width/height — adjust if HUD layout differs from the expected 1280x720
