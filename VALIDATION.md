# Validation

This page separates observed local checks from CI and hardware work still needed.
A successful native clipboard-format test does not prove that every destination
app accepts that format.

## Observed local results

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
