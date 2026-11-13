# Architecture

LocalDrop has one protocol implementation and two thin OS integrations.

```text
Android Share / selected text / native send screen
    │ content URIs + user-provided text
    ▼
Kotlin staging queue → JNI → Rust core
    │ UDP discovery → authenticated Noise session → streamed TCP batch
    ▼
Windows Rust receiver → verified originals + committed receipt
    │
    ├─ CF_UNICODETEXT: text
    ├─ CF_DIB: a supported single image
    └─ CF_HDROP: original files / multiple images
    ▼
Ctrl+V in the destination app
```

## Source map

| Directory | Responsibility |
| --- | --- |
| `crates/core` | Discovery, authenticated sessions, validation, streaming, atomic receipts |
| `crates/android` | JNI wrappers around the shared core |
| `android` | Native Views, share intents, URI staging, QR camera, JobScheduler, Quick Settings |
| `desktop/src` | Tauri host, DPAPI configuration, native clipboard, Win32 tray and notices |
| `desktop/ui` | Local HTML, CSS and JavaScript; loaded only for the app window |
| `design` | Original design reference, visual brief and editable vector mark |
| `scripts` | Build, icon generation and opt-in firewall setup |

## Pairing and discovery

A Windows QR provisions a random 256-bit shared secret and an opaque pairing ID.
Android stores it in private app storage; Windows protects its configuration with
DPAPI for the current user. One laptop/phone trust relationship is supported.

Discovery uses UDP 45872. Transfer sessions use TCP 45871 with
`Noise_NNpsk0_25519_ChaChaPoly_SHA256`. Discovery messages are untrusted hints;
authentication happens in the subsequent Noise handshake. The opaque discovery
ID is stable and can be observed by other local-network participants.

There is no cloud endpoint, account, Internet relay or idle reconnect loop.
Android discovers and connects when a share is requested. Windows listens in the
tray. Connectivity and pairing are separate states: a trusted device can be offline.

## Transfers and retry

Android copies content-provider bytes into its private outbox while URI grants
are valid. This permits retries after the originating app or share activity exits.
JobScheduler retries with exponential backoff; Android may defer jobs in Doze or
under vendor battery policies. Force-stopped apps need to be opened again.

Files stream in 48 KiB chunks. Frames are bounded, with a maximum of 128 files,
32 KiB text and 8 GiB per protocol batch. Receiver cache quotas can be lower.
Incoming originals are checked against their SHA-256 digests before the batch is
committed. Interrupted batches do not appear in history or the clipboard.

A receipt permits a completed batch to be retried without duplicating originals
when an acknowledgement was lost. The clipboard is republished before retry
acknowledgement. Interrupted file transfers restart from the first byte; byte-range
resume is not implemented.

## Clipboard formats

Text uses Unicode text. PNG, JPEG, WebP and BMP can be decoded to an actual Windows
bitmap, with a 16-megapixel cap and bounded decoder allocation. Bitmap conversion
necessarily allocates decoded pixels; file streaming does not load whole originals
into RAM. DIB conversion discards transparency for compatibility.

Multiple files use `CF_HDROP`. For applications accepting only one image at a time,
the explicit **Copy next image** action publishes successive bitmap images. The app
does not inject keystrokes or automate the destination. Each destination decides
which clipboard formats and attachment counts it accepts.

## Reverse direction

Windows stages its current clipboard when **Send clipboard** is clicked. Android
fetches that outbox when **Receive** is tapped. Text is copied while the Android
activity is foreground; files are exposed through a read-only URI provider.
There is no always-on Android receiver service.

## Resource and storage policy

- The Windows tray and notices are native Win32. Closing the app window destroys
  its webview while the receiver continues running.
- Android runs no permanent service and never polls the background clipboard.
  Optional shake detection samples at 10 Hz only while the main activity is active.
- Default outbox/inbox quotas are 1 GiB per direction on Windows and 1 GiB for
  the Android outbox. The configured Windows quota pauses transfers when full.
- The overview shows three transfers. This is a display limit; cached originals
  and receipts remain until explicitly removed.
- Payloads are stored unencrypted at rest. Do not remove cached files while a
  clipboard paste or upload may still be reading them.

See [security](../SECURITY.md), [building](BUILDING.md), and [validation](../VALIDATION.md).
