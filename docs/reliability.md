# Reliability baseline and development trial gates

This is the P0 reliability work for the 0.6.0 development cycle. New editing
features can proceed independently; an unexplained native application crash
on a supported runtime continues to block a stable release. The legacy API35
x86_64 16KiB image has a known kernel/GC incompatibility and now uses a corrected
official kernel; see [the diagnosis and evidence boundary](android-16k-runtime.md). A green run does not close an intermittent
failure without evidence of its cause and correction.

## Separate failure records

| ID | Observed evidence | Status and next discriminating check |
| --- | --- | --- |
| CI-ADB-01 | Runs [38880754331](https://github.com/JasonHe17/NijiFrame/actions/runs/38880754331) (API36) and [38880754519](https://github.com/JasonHe17/NijiFrame/actions/runs/38880754519) (API29/36) exit at `adb root` with `unable to connect for root: closed`, before application tests | Preparation now tolerates a closed root request only when a bounded reconnect verifies uid 0. Three requests maximum, each command has a timeout. Fault checks cover closed/offline transport, a misleading success response, unsupported root and a hung command. This addresses premature workflow exit; it does not establish the underlying transport cause. |
| RUNTIME-16K-01 | Run [38878652710](https://github.com/JasonHe17/NijiFrame/actions/runs/38878652710), API35/16KiB x86_64, application main-thread SIGSEGV at address `0x110`; first frame is JIT-compiled `LayoutNodeDrawScope.draw`, after 37 completed tests | Legacy kernel/GC configuration corrected in CI using a verified official GKI kernel, while retaining API35 userspace and 16KiB tests; [diagnosis](android-16k-runtime.md). The particular historical JIT stacks are consistent with runtime corruption but not deterministically reproduced. Preserve the original failures; any crash on the corrected supported runtime remains a blocker. JIT is unchanged. |
| SYSTEM-HOME-01 | Same run, API37: Pixel Launcher focus-event ANR, visible system dialog, 11 UI failures out of 53 | Open system interference. Preparation now waits for package/activity services and a successful HOME launch before application tests. This is readiness evidence, not a demonstrated fix for later ANRs. Keep system dialogs and failures visible. |

Earlier API35 evidence also includes a MediaProvider crash and a different
ActivityResultRegistry/JIT crash on [38771877787](https://github.com/JasonHe17/NijiFrame/actions/runs/38771877787).
Do not collapse these different signatures into one proven root cause. Successful
later runs are useful negative observations, not a resolution of those crashes.

## Evidence and test execution

CI records `emulator-phase.txt`: boot, framework preparation, application tests,
then the exact application test exit status. Failure during either preparation
phase means the application suite did not run, not that it passed. Application
tests run once. Setup retries do not rerun failed tests.

The EXIT handler gathers all logcat buffers, properties, activity/window and
DropBox state, a screenshot, tombstones, ANR files and available UI evidence
before stopping the emulator, including setup failures. Individual collection
commands have timeouts; a missing file or disconnected device is recorded, not
treated as successful evidence capture. CI uses disposable synthetic data only.

For a dedicated local emulator:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/prepare-test-emulator.py --serial emulator-5554 --page-size 16384
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
bash scripts/collect-emulator-evidence.sh emulator-5554 artifacts/emulator-evidence
```

Preparation changes the test runtime heap to 512MiB and restarts the framework.
Use a dedicated emulator, close it without saving a snapshot, and cold boot to
restore image defaults. Normal-memory refusal tests inject constrained budgets;
the larger positive-test heap is not a phone performance claim.

## Recovery and delivery gates

Preserve original bytes and applied edits through failed/cancelled writes, saved
project reopen, portable v1–v6 reads and an actual process stop/relaunch. Existing
integration, export-safety and large-image suites cover provider failures,
preparation cancellation, admission refusal before destination open, and saved
state restoration. UI recreation alone does not verify process recovery.

The export panel uses the same decode/render/transform/codec memory estimate as
export admission. It includes current draft geometry, never changes the chosen
size silently, offers a fitting smaller size when available, and keeps editable
project output available. Native-scale detail inspection and cancelling the
panel preserve drafts. Admission runs again at export time; changing free memory,
encoder scratch, temporary storage and provider failures remain possible.

Use the [trial tasks](manual-check.md#two-short-phone-tasks) and
[signing/update procedure](releases.md). Physical-phone tasks, complete TalkBack
acceptance and production signing are separate gates. An unavailable phone test
must remain explicitly pending; emulator results cannot fill it in.


The same-commit develop run [38913733755](https://github.com/JasonHe17/NijiFrame/actions/runs/38913733755) later failed one of 54 UI checks in the legacy API35/16KiB environment. Its evidence includes separate GMS native and SystemUI ReferenceQueueDaemon crashes. The other five Android jobs and build passed; the earlier green run remains historical evidence, not a resolution of this subsequent failure. See the kernel diagnosis above.
