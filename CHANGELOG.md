# Changelog

## 0.4.0 — 2026-11-21 (Android and Windows)

- Pairing no longer depends on the Wi-Fi it was made on. Android discovery now
  sends directed broadcasts on every local IPv4 interface, so a paired phone finds
  the laptop on any shared Wi-Fi and when the laptop joins the phone's hotspot
  (where the limited broadcast left via mobile data). Discovery retries once.
- `scripts/firewall.ps1` now allows the Public profile too (still local-subnet and
  executable-scoped), because Windows marks new Wi-Fi and hotspots as Public.
  `-PrivateOnly` keeps the old behaviour; re-running replaces existing rules.

## 0.3.4 — 2026-11-18 (Android update)

- Received videos now save to Gallery under Movies/LocalDrop on Android 10+,
  from the widget and in-app Receive. Video bytes stream without re-encoding;
  originals remain in the private inbox. Repeat exports avoid duplicate copies.
- Recognise video extensions on generic file references sent by Windows 0.3.1.
- Preferences' Gallery export now includes previously received photos and videos.

## 0.3.3 — 2026-11-18 (Android update)

- Received images appear in Gallery under Pictures/LocalDrop on Android 10+,
  from both Receive and the home-screen widget. Clipboard BMPs become PNGs;
  original files remain in the private inbox. Repeat exports of the same batch
  avoid duplicate copies. No broad storage permission is requested.
- Received images open in a bounded, sampled preview inside LocalDrop instead
  of relying on an external viewer. Preferences and the preview offer Gallery
  saving for earlier received batches.

## 0.3.2 — 2026-11-17 (Android update)

- Home-screen Receive widget with a direct activity PendingIntent, no periodic
  updates, and a compact foreground receive panel. Text publication waits for
  window focus; the widget contains no clipboard content or pairing information.
- Preferences offers the launcher's standard widget-pinning prompt. No new
  permissions, services, or dependencies; existing pairing is retained.

## 0.3.1 — 2026-11-17 (Windows update)

- Android **Receive** fetches the current laptop clipboard from the tray without
  opening Windows LocalDrop or clicking Send. Existing Android 0.3.0 is compatible.
- No clipboard watcher or automatic snapshot history. Text stays in memory and
  original files stream in place; request-owned bitmap spools are cleaned up.
- A Windows preference controls on-demand clipboard access. Explicit manual queues
  remain available and take precedence; empty clipboards do not resend stale data.

## 0.3.0 — 2026-11-13

Initial public development preview, consolidating the working MVP and UI updates.

- Authenticated local discovery and encrypted streaming transfers in Rust.
- Native Android Share and selected-text handlers, durable staging and retry.
- Windows tray receiver with native text, bitmap and multi-file clipboard formats.
- Explicit next-image copying for applications accepting one image per paste.
- Foreground reverse receive on Android, Quick Settings picker and opt-in shake.
- Paired-device status, three-item visible history and generic transfer notices.
- Transfer desk design for Android and Windows, with dedicated Preferences.
- Higher-contrast Android action buttons after physical-device feedback.
- MIT licensing, contributor documentation and automated build checks.

This is a development preview: Windows binaries are unsigned, Android APKs are
development-signed, and destination-app paste behavior still needs broader testing.
