<div align="center">

<img src="docs/launcher-icon/png/control-switch-icon-512.png" width="88" alt="Control Switch icon">

# Control Switch for AYN Thor

**Play docked with the controller in your hands — no remapping, no root.**

![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--2.0-blue)
![Root](https://img.shields.io/badge/root-not%20required-brightgreen)
![Device](https://img.shields.io/badge/AYN%20Thor-tested-FF8A1F)

A fork of [Ezequiel-CE/Docking-Enhancer](https://github.com/Ezequiel-CE/Docking-Enhancer), the Odin 2
app, retargeted to the AYN Thor and renamed Control Switch.

<br>

<img src="control-switch-ui.png" alt="Main screen: the Thor and its battery, the mirrored controller as player 1, and the four menu rows" width="820">

<table>
<tr>
<td><img src="control-switch-ui-3.png" alt="Change Input Order: press L + R on the controller you would like to use, then A"></td>
<td><img src="control-switch-ui-2.png" alt="Button mapping in Switch icons, with Home bound to Minus + Plus held together"></td>
</tr>
<tr>
<td align="center">Press <b>L + R</b> to pick player 1</td>
<td align="center">Bind any button, or a combination</td>
</tr>
<tr>
<td><img src="control-switch-ui-chord.png" alt="Binding a combination: the held buttons appear live as icons"></td>
<td><img src="control-switch-ui-light.png" alt="Additional Settings in the Light theme"></td>
</tr>
<tr>
<td align="center">Hold buttons together, then let go</td>
<td align="center">Dark, Super dark or Light</td>
</tr>
<tr>
<td colspan="2"><img src="control-switch-ui-tests.png" alt="Controller Tests: a live Switch-style drawing of the pad lights up as you press, and a checklist ticks off every button, stick circle and trigger"></td>
</tr>
<tr>
<td colspan="2" align="center">Test every button and stick on any controller</td>
</tr>
</table>

</div>

## New in 0.6.0

- **Controller tests** — pick the Thor's own pad or any controller and press everything: a live
  drawing lights up, and a checklist ticks off every button, both sticks (a full circle plus the
  click) and both triggers. Hold **B** to leave
- **Motion test** — **Y** on the same page turns the pad's gyro into three rows (pitch, yaw, roll)
  with a rolling ball and live deg/s, reading whichever gyro the DSU port is serving
- **Steadier gyro** — the Thor's drift filter was sized for 50 Hz and was absorbing smooth turns at
  200 Hz; emulators now get the full turn

## What it does

Dock your handheld to a TV and games still expect its *built-in* controller, not the pad you're
actually holding — so every session starts with remapping. Control Switch grabs your external
controller and replays its input onto the internal controller's input device, so everything just
sees the built-in pad. Nothing to remap, ever. It can do the same undocked, whenever a controller
is connected.

It runs without root, using a privileged service the stock firmware already ships.

## Features

- **Automatic** — mirrors **Only when docked** or **Always when a controller is connected**, your
  choice, and survives reboots
- **Player 1 lock** — press **L + R** on the controller you want, Switch-style. Once locked, only
  that pad is mirrored, whenever it's connected. Identical pads are told apart while connected
- **Home button that goes Home** — the external pad's Home/Guide button goes to the launcher, or
  Back, or passes through to games. Hold it for **Recents**
- **Home and Back on any button** — bind either to a pad button, or to a combination, in the
  mapping editor
- **Button combinations** — any button, Home and Back included, can be bound to 2–4 buttons held
  together. Hold them, let go, done; the held buttons show as icons while you do it. When a
  combination fires, its buttons don't also reach the game
- **Rumble on the pad** — when a game vibrates the Thor, your controller rumbles instead, at the
  **Rumble strength** you pick (Android reports no intensity, so every rumble plays at that one level).
  Needs a pad whose driver supports rumble
- **Gyro for emulators** — serves motion over DSU (cemuhook) on `127.0.0.1:26760`: the Thor's gyro
  handheld, your controller's gyro while mirroring (Switch-style pads)
- **Controller tests** — a live drawing of the pad lights up as you press, and a checklist ticks
  off every button, both sticks (a full circle, plus the click) and both triggers, for the Thor's
  own pad or any controller; hold **B** to leave. **Y** tests motion instead: turn the pad about
  each axis, with a ball that rolls to the low side and live deg/s (a controller's gyro needs
  mirroring to be allowed, since the daemon reads it). The Thor's Home key can't be tested in-app
- **Select + Start** — hold it (3s) to close the current app
- **Hides the external pad** while mirroring, so games see one controller instead of a phantom player 2
- **Guided button mapping** — press a button for each row, or let **Map all** walk the list; or pick
  **Nintendo** or **Xbox** layout to map in one go. Triggers and stick halves can fill button
  slots, and untouched controls keep working. Pads the bundled
  [SDL database](third-party/gamecontrollerdb/NOTICE.md) knows arrive already mapped, using the
  entry that fits the pad's driver

- **Nintendo layout aware** — swaps A/B and X/Y when the built-in pad is in its Nintendo profile
- **Controller-first UI** — Switch-style screens with a button-hint bar; set it up without touching
  the screen
- **Themes** — Dark, Super dark or Light, in Additional Settings
- **Button prompts that match your pad** — Xbox Series icons by default, Nintendo Switch 2 icons
  once you apply the Nintendo layout
- **Add to home screen** — for game frontends like Cocoon Shell that hide the Android app list
- **~0.04 ms of added latency** (median) — a fraction of one 60Hz frame
  ([measured](docs/TECHNICAL.md#latency); re-measured on a Thor, see [Building](#building))

## Gyro in emulators

Control Switch serves motion over DSU (the cemuhook protocol) on `127.0.0.1:26760`: the Thor's own
gyro while you play handheld, with its resting drift removed, and your controller's gyro while
mirroring. Set an emulator up once and it follows whatever is in your hands. Controller gyro needs
a Switch-style pad (Pro Controller, 8BitDo in Switch mode); the Thor's firmware never switches their
motion sensors on, so Control Switch does.

Before setting an emulator up, **Additional Settings → Controller tests → Y** shows the motion the
port is serving right now, so you can tell a wiring problem from an emulator setting.

| Emulator | DSU on Android | Setup |
| --- | --- | --- |
| Eden | yes | In `config.ini`: `enable_udp_controller=true`, and `player_0_motionleft` / `player_0_motionright` = `"engine:cemuhookudp,guid:0000000000000000000000007f000001,motion:0,pad:0,port:26760"` |
| Dolphin | yes | `DSUClient.ini`: `[Server] Enabled = True`, `Entries = Control Switch:127.0.0.1:26760;`. Map the Wii Remote's motion to `DSUClient/0/DualShock 4` |
| Azahar | yes | Motion source "cemuhookudp", address `127.0.0.1`, port `26760` |
| Cemu | no | The Android build has no DSU client; it reads the Thor's own sensors |

## Install

Updating from Control Switch 0.3.0? Just install over it; settings and mappings carry over.

Coming from Docking Enhancer v0.2.0? Uninstall it first. The app id changed (now
`com.controlswitch`), so the new version installs as a separate app instead of upgrading, and two
copies would fight over the controller. Settings don't carry over.

1. Install the APK from this fork's [Releases](https://github.com/CardinalBird/control-switch/releases)
   (allow "unknown sources" if prompted), or build it yourself — see [Building](#building).
   Upstream's releases don't include the Thor changes.
2. Open it once and allow notifications. Under **Additional Settings → Allow mirroring**, choose
   **Only when docked** or **Always** (whenever a controller is connected).
3. Pair a controller (and dock, if you chose docked only). It takes over on its own.
4. Optional: with more than one pad, open **Change Input Order** and press **L + R** on yours,
   then **A**. On a frontend launcher, use **Add to home screen** to pin the app.

## Supported devices

| Device | Status |
| --- | --- |
| **AYN Thor** | Tested (Android 13, firmware `Thor_V1.0.0.377`) — primary target of this fork |
| AYN Odin 2 / Portal | Upstream's target; not re-tested with this fork's changes |
| AYN Odin 2 Mini | Not supported — [ships the service but it doesn't respond](docs/TECHNICAL.md#the-odin-2-mini) |
| Other handhelds with `PServerBinder` | Likely work, untested |

Support is decided at runtime by whether the firmware's service actually answers — not by a model
list — so any handheld with a working one has a good chance. Devices without it are told so instead
of silently doing nothing.

## On the Thor

What's different from the Odin, all found on a real device:

- **The bottom screen isn't a dock.** It's a second, always-on display, so it's ignored when
  deciding whether you're docked; only an external display counts.
- **The built-in pad** reports as "Odin Controller" (`2020:0111`), so it's detected automatically.
- **Only one external pad is taken over by the firmware.** The Thor re-registers one connected pad
  under its own ID; that's the one the app can hide. If your locked player 1 is a *second* pad, the
  first stays visible to games as player 2 while mirroring.
- **Locks are remembered by name across reconnects.** Two identical pads (two Pro Controllers) are
  told apart while connected; after both reconnect, the lock may land on either. The Pro
  Controller doesn't expose its Bluetooth address to the system.
- **Recents** opens when Home is released after a long hold, not at the moment the hold counts.

Tested handheld and on a real HDMI dock, with a Nintendo Switch Pro Controller and an 8BitDo
Ultimate 2 (Switch mode) over Bluetooth.

## How it works

A small native daemon reads the external controller, grabs it exclusively, and replays every event
onto the internal controller's `/dev/input` node. While mirroring it also unlinks the external pad's
node so Android drops it from the device list, and puts it back on exit.

Privileged work goes through the stock firmware's `PServerBinder` service via a raw binder
transaction — the same no-root technique used by ClusterTune and PULSE.

Details, design notes and the build instructions live in **[docs/TECHNICAL.md](docs/TECHNICAL.md)**.

## Building

Needs JDK 21 for Gradle (the project compiles for Java 17), the Android SDK (compile SDK 35) and an
NDK (26.3 used here). On macOS or Linux:

```sh
# Native daemon -> android/app/src/main/assets/input_mirror/ (commit it with its source)
ANDROID_NDK_HOME=/path/to/ndk scripts/build-input-mirror.sh

# Unit tests and a debug APK
cd android
./gradlew :domain:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `scripts/build-input-mirror.ps1` and `gradlew.bat`. Debug builds act as if always
docked (`FORCE_DOCK_MODE_FOR_DEV` in `android/app/build.gradle`) so the mirror can be tested at a
desk; release builds use real dock detection. Debug and release are signed differently, so
switching between them means an uninstall, which resets the app's settings.

On-device tests drive synthetic controllers, so no physical pad is needed:

```sh
ANDROID_NDK_HOME=/path/to/ndk .claude/skills/mirror-e2e/scripts/build-harness.sh
adb shell sh /data/local/tmp/e2e.sh
adb shell sh /data/local/tmp/latency.sh 300
```

The Thor has no root shell, so the three assertions that unlink device nodes directly fail there by
design; the app itself does that step through `PServerBinder`. On a Thor the rest of the suite
passes (81 of 84), and the latency run gives a median of ~0.04 ms added (65 µs mirrored against
22 µs without the daemon). Its slowest 5% are higher (~0.4 ms) because the test daemon runs at
normal priority; the app's runs at high priority.

## Credits

This is a fork of [**Docking Enhancer**](https://github.com/Ezequiel-CE/Docking-Enhancer) by
Ezequiel-CE, which did the hard part: the daemon, the no-root plumbing and the mapping system.

The no-root PServer technique comes from [**ClusterTune**](https://github.com/AurelioB/ClusterTune),
and the `PServerBinder` access code is adapted from [**PULSE**](https://github.com/keiretrogaming/pulse).
Button prompts are [Kenney Input Prompts](https://kenney.nl/assets/input-prompts) (CC0).
Full third-party notices in [NOTICE.md](NOTICE.md).

Built with heavy help from an AI coding assistant (Anthropic's Claude), always directed and reviewed
by the maintainers — every change compiled, unit-tested and confirmed on a real handheld before
shipping. Spelled out here for transparency.

Not affiliated with AYN.

## License

[GNU General Public License v2.0](LICENSE). Please keep the attribution above intact in any fork —
the GPL requires it.
