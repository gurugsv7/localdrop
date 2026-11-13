# Contributing

Small, focused changes are welcome. Read the [architecture](docs/ARCHITECTURE.md)
and [build instructions](docs/BUILDING.md) before changing platform integration.

## Priorities

1. Reliable Android Share → native Windows clipboard → Ctrl+V.
2. Multi-image interoperability and useful errors in real destination apps.
3. Retry, reverse transfers and supported Android shortcuts.
4. Clear UI with low idle resource use.

Please discuss large dependencies, new background services or protocol changes
in an issue first. Preserve the supported Android permission model. Accessibility
workarounds, keystroke injection and background clipboard polling are out of scope.

## Pull requests

Create a branch from `main` and keep changes scoped to one problem. Explain the
trigger, the resulting behavior and how it was verified. Separate behavioral
changes from broad formatting. Include synthetic-content screenshots for UI work.

Run the checks relevant to the change:

```sh
cargo fmt --all -- --check
cargo test --locked -p localdrop-core -p localdrop-android -j 2
```

Windows changes also need a native build and clipboard/receiver checks where
appropriate. Android changes need APK compilation and lint. Follow
[VALIDATION.md](VALIDATION.md) for hardware checks. Say explicitly which devices,
formats or OS flows you did not test.

## Privacy and development tooling

Use synthetic files and text. Keep `config.bin`, QR codes, signing keys, local
properties, device captures, outboxes, inboxes and transfer receipts out of Git.
Do not paste private clipboard text or machine paths into issues.

The optional Android instrumentation runner is separate from the installed app.
Do not add UiAutomation or an Accessibility service to automate copied-text tests;
test the user action manually. Disconnect temporary debugging after testing.

## Dependencies and licensing

Prefer existing standard-library/platform capabilities. Explain binary, RAM,
storage, CPU and battery costs before adding a dependency. Keep `Cargo.lock`
updated and pin wrapper/tooling changes. Contributions are made under the
repository's [MIT license](LICENSE); retain applicable third-party notices.
