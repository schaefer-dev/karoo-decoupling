# karoo_decoupling

A Karoo extension that displays **live aerobic decoupling** (Pa:Hr drift) on your
Hammerhead Karoo 2 / Karoo 3 while you ride.

---

## 1. What it does

Aerobic decoupling compares the [Efficiency Factor](https://www.trainingpeaks.com/learn/articles/aerobic-endurance-and-decoupling/)
(`EF = average power / average heart rate`) of the **first half** of a ride against
the **second half**. A small change (under ~5%) suggests good aerobic endurance —
your heart rate stayed roughly proportional to your power output. A larger change
suggests cardiac drift, which is a useful pacing/training signal.

This extension samples power and heart rate once per second of **moving time**
(stopped time is excluded automatically — Karoo's `ELAPSED_TIME` stream pauses on
`RideState.Paused`), splits the ride at the midpoint of total moving time, and
shows the drift percentage, e.g. `+4.2%` (positive = HR rose more than power;
classic decoupling) or `-1.8%` (power rose more than HR). EF1 and EF2 are
computed internally but not rendered in the widget — only the drift figure
appears on screen.

The widget shows `—` for the first 120 seconds of moving time (not enough data),
then displays your running Pa:Hr — a live preview of what your final drift would
be if you ended the ride right now. Release builds repaint the value about once
every 30 seconds to save battery (the math still uses every per-second sample;
only the on-screen redraw is throttled). Debug builds repaint on every tick — see
§10 for the full debug-vs-release behavior table.

**Example.** Hour 1 averages 200 W / 145 bpm → EF1 = 1.379. Hour 2 averages
200 W / 158 bpm → EF2 = 1.266. Drift = (1.379 − 1.266) / 1.379 × 100 = **+8.2%**.
That's a meaningful decoupling and is a sign the second hour was harder than the
first relative to your power.

---

## 2. Requirements

- **Hardware:** Hammerhead Karoo 2 or Karoo 3 with a paired power meter and heart
  rate monitor.
- **Build host:** macOS or Linux with **JDK 17**, the Android command-line tools,
  and `adb` on `PATH`. Android Studio is optional.
- **GitHub account** with access to the `hammerheadnav/karoo-ext` GitHub Packages
  repository. The Karoo SDK is hosted there and the build pulls it on demand.

---

## 3. Get GitHub Package credentials

The Karoo SDK is published to GitHub Packages, not Maven Central. You need a
personal access token (PAT) with the `read:packages` scope.

1. Visit <https://github.com/settings/tokens> and create a **classic** PAT with
   only the `read:packages` scope. Copy the token immediately.
2. Add credentials to `~/.gradle/gradle.properties` (creating the file if it
   does not exist):

   ```properties
   gpr.user=your-github-username
   gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
   ```

   Alternatively, export environment variables before invoking Gradle:

   ```sh
   export USERNAME=your-github-username
   export TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
   ```

   The project's `settings.gradle.kts` accepts either form.

---

## 4. Build the APK

From the repo root:

```sh
./gradlew :app:assembleDebug
```

The signed-with-debug-key APK lands at:

```
app/build/outputs/apk/debug/karoo_decoupling-0.1.0-debug.apk
```

For a release build:

```sh
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/karoo_decoupling-0.1.0-release-unsigned.apk
```

The release variant **disables** the on-device data simulator (see §10). Use it
for actual riding.

---

## 5. Enable USB debugging on the Karoo

1. On the device, open **Settings → About**.
2. Tap **Build number** seven times to unlock Developer Options.
3. Open **Settings → System → Developer Options** and toggle **USB debugging**
   on.
4. Connect the Karoo to your host via USB-C (Karoo 3) or micro-USB (Karoo 2).
5. When the device prompts to allow USB debugging, accept it. Verify on your
   host:

   ```sh
   adb devices
   # should list the karoo as "device" (not "unauthorized")
   ```

For more, see Hammerhead's docs: <https://github.com/hammerheadnav/karoo-ext>.

---

## 6. Install

```sh
adb install -r app/build/outputs/apk/debug/karoo_decoupling-0.1.0-debug.apk
```

Verify:

```sh
adb shell pm list packages | grep karoo_decoupling
# package:com.karoo_decoupling
```

---

## 7. Add the widget to a ride profile

On the Karoo:

1. **Settings → Extensions** — enable **Decoupling** (the extension must be
   enabled before its data field shows up in the picker).
2. **Settings → Ride Profiles → (choose profile) → Data Pages → Edit**.
3. Pick a slot, tap **Add Field**, scroll to **Aerobic Decoupling** under the
   Decoupling extension, and confirm.

---

## 8. What to expect during a ride

| Time elapsed (moving)  | Widget shows                                                          |
|------------------------|-----------------------------------------------------------------------|
| 0 – 119 s              | `—`                                                                   |
| 120 s and beyond       | `+X.X%` — repaints every ~30 s in release, per-second in debug        |
| After Stop → Start     | Resets back to `—` for the next 120 s                                 |

If power or HR is missing for a moment the widget keeps using the last-known
value rather than blanking out. Pauses don't show as gaps — moving time is what
the widget knows about.

---

## 9. Testing without a device

The bulk of bugs are caught by JVM unit tests, no Karoo required.

```sh
./gradlew :app:test                       # all unit tests
./gradlew :app:testDebugUnitTest          # debug variant only
./gradlew :app:testDebugUnitTest --tests "*Coordinator*"   # stream-coordination tests
./gradlew :app:testDebugUnitTest --tests "*Calculator*"    # pure math tests
```

There are two test layers:

### Layer 1 — `DecouplingCalculatorTest`

Pure math: drift formula, the 120 s threshold, half-coverage guards, zero-EF
guards, duplicate-second dedup, four-hour stability. Add a case here whenever
you change `DecouplingCalculator.kt`.

### Layer 2 — `DecouplingCoordinatorTest`

The stream-coordination logic (HR + Power + RideState + ELAPSED_TIME) is
extracted into `DecouplingCoordinator`, which takes four `Flow`s as input and
emits one `DecouplingResult?` per ELAPSED_TIME tick. Tests drive it with
deterministic cold or hot flows under `kotlinx.coroutines.test.runTest` — no
mocking framework, no Android instrumentation.

**Recipe for adding a coordinator test:**

1. Open `app/src/test/kotlin/com/karoo_decoupling/extension/DecouplingCoordinatorTest.kt`.
2. Copy one of the existing tests (e.g. `happy path emits null until 120s …`).
3. Modify the four input flows (`hr`, `power`, `ride`, `elapsed`) to model the
   scenario you want to verify.
4. Assert on the emitted list — typically a final `DecouplingResult?` or a
   transition (`emissions[119]` null, `emissions[120]` non-null, etc.).

Use `MutableSharedFlow` + `testScheduler.runCurrent()` if you need to interleave
events (see the Idle→Recording reset test for the pattern).

---

## 10. Debug vs release widget behavior

Two things differ between the `debug` and `release` APKs. Both are gated on
`BuildConfig.DEBUG`, so a release build cannot leak debug behavior.

| Aspect                    | Debug (`assembleDebug`)                                         | Release (`assembleRelease`)                                                    |
|---------------------------|------------------------------------------------------------------|--------------------------------------------------------------------------------|
| Data source               | Deterministic simulator (`SimulatedStreams.kt`), no sensors      | Real HR + power streams from `KarooSystemService`                              |
| Simulated-time pace       | 10× wall-clock (`TICK_MS = 100 ms`) — a full 8-min test ride elapses in ~48 s | n/a — runs on actual moving time                                               |
| Widget repaint cadence    | Every tick (~100 ms in debug; the simulator drives rendering)   | ~Once every 30 s, plus one extra paint at the warm-up→first-value transition   |
| Display marker            | Trailing `*` (e.g. `+11.3% *`) so synthetic data is unmistakable | No marker                                                                       |

The 30 s release cadence is a battery optimization: the math layer
(`DecouplingCalculator`) still consumes every per-second sample, so EF1/EF2
remain accurate; only the RemoteViews redraw is throttled. The Karoo SDK
documents "at least once per minute" as the liveness floor — 30 s sits well
inside that and keeps the field feeling responsive when drift changes (e.g.
during interval transitions) without burning CPU on per-second redraws of a
slow-moving number.

### Running the on-device simulator

The debug APK injects a deterministic synthetic ride instead of reading real
sensors — useful for validating the rendering and lifecycle path on actual
Karoo hardware without going on a ride.

- Build: `./gradlew :app:assembleDebug`.
- Install via §6.
- Add the field to a profile (§7) and start a ride.

The simulator (see `SimulatedStreams.kt`) emits:

| Moving second | Power | HR                                |
|---------------|-------|-----------------------------------|
| 0 – 240       | 200 W | 140 bpm (flat warmup)             |
| 240 – 480     | 200 W | 140 → 170 bpm linear ramp         |
| > 480         | 200 W | 170 bpm                            |

Expected drift after ~8 minutes: **roughly +10 to +12 %**. While the simulator
is active the displayed value carries a trailing `*` marker (e.g. `+11.3% *`)
so you cannot mistake it for live data.

Because of the 10× speedup, the full simulated ride completes in ~48 s of
wall-clock time, and the debug widget paints on every tick so you can see the
value change smoothly. The release APK never activates the simulator and uses
the 30 s repaint cadence described above.

---

## 11. Troubleshooting

| Symptom                                                                       | Fix                                                                                          |
|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| Gradle fails with `401 Unauthorized` from `maven.pkg.github.com`              | PAT missing or wrong scope; recreate with `read:packages`, re-save to `gradle.properties`.   |
| "Aerobic Decoupling" not in the data-field picker                             | Extension is not enabled. Settings → Extensions → toggle Decoupling on.                      |
| Widget stuck on `—` indefinitely                                              | Either HR or power is not paired; or you have not yet accumulated 120 s of moving time.      |
| `adb install` reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`                    | A previous build with a different signing key is installed. `adb uninstall com.karoo_decoupling` then reinstall. |
| Trailing `*` appears next to drift values                                     | You installed the debug APK — sensor data is synthetic. Install the release APK for real rides. |
