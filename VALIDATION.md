# Validation

This page separates observed local checks from CI and hardware work still needed.
A successful native clipboard-format test does not prove that every destination
app accepts that format.

## Observed local results

### Android 0.3.4 — 2026-11-18

- APK, device-test APK and lint passed (0 errors, 27 warnings). Installed in place
  on the paired Android 14 / ARM64 phone; version code 6.
- A synthetic one-second H.264 MP4 was placed on the Windows file-reference
  clipboard and fetched through the production widget receive panel over Wi-Fi.
  Android recognised the generic Windows MIME as video/mp4 by extension.
- The Gallery video was published as a non-pending Movies/LocalDrop entry. Its
  SHA-256 matched the laptop original, duration metadata was present and a frame
  decoded at the expected dimensions. Re-exporting the same batch did not create
  another copy. This does not verify every video codec or a hosted launcher tap.
- Existing received media export completed; temporary test helper removed and
  laptop clipboard restored. Windows Share-panel integration remains unsupported;
  use File Explorer Ctrl+C, then Android Receive.

### Android 0.3.3 — 2026-11-18

- APK, device-test APK and lint passed (0 errors, 27 warnings). Installed in place
  on the paired Android 14 / ARM64 phone; version code 5.
- On-device MediaStore tests published PNG and Windows-style BMP fixtures into
  `Pictures/LocalDrop`, verified public/non-pending rows and exact decoded pixels,
  and confirmed repeat export did not duplicate the same batch. The internal
  sampled BMP preview decoded successfully.
- A synthetic native Windows clipboard bitmap crossed the real paired Wi-Fi
  connection through the widget's production receive panel and appeared in
  MediaStore as a PNG with exact pixels. The laptop clipboard was restored.
- Existing received images were exported to the Gallery album. Test fixtures are
  tiny 2×3 images; originals remain in the private inbox.
- The temporary instrumentation package was removed. No Accessibility or
  UiAutomation was used. The hosted launcher tap and visual Gallery-app UI were
  not automated; Gallery availability was checked through its MediaStore source.

### Android 0.3.2 — 2026-11-17

- APK, device-test APK and Android lint built successfully (0 errors, 24 warnings;
  warnings include localization, API-specific widget sizing and existing metadata).
- Installed in place on the paired Android 14 / ARM64 phone; version code 4.
- On-device instrumentation verified widget-provider registration, zero periodic
  updates, successful RemoteViews inflation and a compact rendered preview.
- The production receive panel fetched exact synthetic Windows clipboard text
  through the existing pairing over real Wi-Fi and published it to the focused
  Android clipboard. The native Rust library was unchanged.
- No Accessibility or UiAutomation was used. The temporary test package was
  uninstalled; the laptop's supported clipboard contents were restored.
- Launcher placement and tapping the hosted widget remain a manual check using
  the launcher's Add confirmation. The device test directly launched the panel.

### Windows 0.3.1 — 2026-11-17

- Release build passed; 11 Rust tests passed across the core and desktop.
- Production receiver diagnostic passed all 10 checks using authenticated local
  sessions and native Windows clipboard formats. New checks cover two successive
  fresh text reads without queueing, original file bytes, bitmap pixels and spool
  cleanup, empty clipboard handling, and disabling on-demand clipboard access.
- Existing pairing migration and persistence of the disabled preference passed.
  The frontend JavaScript syntax check and `git diff --check` also passed.
- Installed the matching release executable and verified its TCP 45871 listener;
  the existing encrypted configuration remains present. No Android update needed.
- This iteration used the Android 0.3.0 wire request in the receiver diagnostic;
  it did not rerun a physical-phone tap over Wi-Fi or inspect the new UI visually.

### Earlier validation

| Check | Result | Scope |
| --- | --- | --- |
| Rust core protocol tests | Passed | Authentication, bounds, streamed bytes, duplicate commits, interrupted batches, quotas |
| Windows pairing configuration migration | Passed | Older configuration retains pairing material and gains defaults |
| Native Windows clipboard checker | Passed | Unicode text, real DIB pixels, file references, sequential image copy |
| Production receiver diagnostic | Passed | Authenticated pairing, text, bitmap, multiple originals and reverse outbox retrieval |
| Android Share intents on Android 14 / ARM64 | Passed in earlier MVP validation | Text, a PNG and multiple PNG originals over real local Wi-Fi |
| Shared original file fidelity | Passed | Phone and Windows SHA-256 digests matched |
| Android selected-text action | Passed in earlier MVP validation | Standard PROCESS_TEXT handler and exact Unicode clipboard text |
| Android copied-text action | Passed in earlier MVP validation | Foreground user action delivers text to Windows |
| Android notification | Observed | Standard notification permission and a private, generic transfer update |
| v0.3 Android build and lint | Passed | Zero errors; 14 warnings, mostly localization and existing packaging metadata |
| v0.3 Android installation and paired state | Passed | In-place physical-device update retained pairing and preferences |
| v0.3 Windows release build and receiver | Passed | Installed version listens with retained pairing and history |
| v0.3 Android UI review | Main and Preferences inspected | Later button contrast correction compiled, linted and installed |
| Final Windows UI interaction review | Incomplete | Desktop automation was stopped before the full review |

The v0.3 UI iteration did not rerun the full physical end-to-end transfer suite.
Protocol and clipboard implementations were unchanged; earlier results above
are historical evidence, not claims of a new regression run.

## UI reference and contrast

The README shows real Windows and Android screenshots supplied by the user.
Private Windows transfer labels are locally blurred; Android image data is
preserved while optional metadata is removed. The original generated design
reference remains in `design/`. The interfaces use native Views and local
HTML/CSS respectively; no raster mockup is used as an app background.

The corrected Android secondary-action palette has these fixed-colour ratios:

| Pair | Ratio |
| --- | --- |
| Ivory label / secondary fill | 9.84:1 |
| Secondary outline / surrounding card | 5.01:1 |
| Secondary outline / button fill | 3.80:1 |
| Primary label / orange fill | 6.87:1 |
| Muted label / dark panel | 6.76:1 |

These calculations are not a complete accessibility certification. Larger fonts,
screen readers, focus, touch targets and different displays need further checks.

## Repeatable developer checks

See [BUILDING.md](docs/BUILDING.md) for toolchain setup.

```sh
cargo fmt --all -- --check
cargo test --locked -p localdrop-core -p localdrop-android -j 2
```

On Windows, `scripts/build-windows.ps1 -CheckClipboard` runs the native clipboard
checker after building. This temporarily replaces clipboard formats and attempts
to restore readable text/files/images; custom app-only formats cannot be restored.

`LocalDrop.exe --diagnostic <scratch-directory>` starts a temporary production
receiver with random test pairing on TCP 45871 / UDP 45872. Quit your regular
receiver first. It uses synthetic content and writes `result.json`. It does not
use or replace the normal pairing configuration. Scratch data is retained for
explicit cleanup.

The optional Android test package is separate development tooling. Do not use
UiAutomation or enable Accessibility automation to test copied-text behavior;
perform that tap manually. The normal app needs neither ADB nor Accessibility.
Disconnect temporary debugging when finished.

## Hardware matrix still needed

- QR scanning, camera permissions and notification prompts on more Android versions.
- Share sheets in Gallery, Photos, browsers, file providers and video apps.
- Multi-attachment and sequential-image paste in individual destination apps.
- Discovery after DHCP changes, sleep/wake, network changes and guest isolation.
- Offline retry through Doze, process death, reboot and vendor battery controls.
- Windows sign-in startup and tray behavior across OS/display configurations.
- Quick Settings and shake sensitivity across phone hardware.
- Reverse receive through the physical Android inbox UI.
- RAM, CPU, throughput, cache growth and phone battery benchmarks.
- A final complete UI review after the latest contrast/layout corrections.

## CI

The repository includes read-only GitHub Actions jobs for Rust core tests,
Windows package compilation and Android ARM64 compilation/lint. Consult the
actual workflow run for current results; the local checks above do not imply
that hosted CI has passed.
