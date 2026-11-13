# Security

LocalDrop is an early development preview. Its protocol uses established
cryptography through Rust libraries, but the application has not received an
independent security audit.

## Report a vulnerability

Use the repository's **Security → Report a vulnerability** page for a private
report: <https://github.com/gurugsv7/localdrop/security/advisories/new>.
Include the affected version, reproduction steps and potential impact. Use
synthetic transfers. Never submit pairing QR codes, shared secrets, private files,
clipboard history or unredacted logs. No response-time guarantee is offered.

## Trust boundary

- The pairing QR is a credential. Anyone who obtains its secret can act as a
  trusted peer. Only scan the QR from your own laptop and never publish it.
- UDP discovery is not authentication. A Noise NNpsk0 handshake authenticates
  the shared secret before payload transfer.
- Pairing is shared-secret provisioning, not separate public-key enrollment for
  an arbitrary collection of devices. One trusted laptop is supported.
- An authenticated peer can replace the clipboard and consume cache capacity.
  Files are not automatically opened and no remote-command feature exists.
- Local-network adversaries may observe discovery identifiers or disrupt
  availability. The protocol is intended for direct local connections, not
  Internet port forwarding.

## Storage and permissions

Windows protects its pairing configuration with current-user DPAPI. Received
payloads and clipboard history are not encrypted at rest. Android stores pairing
and queues in app-private storage with backup disabled. Use OS disk encryption
where appropriate; device/account compromise is outside this application's boundary.

Android requests camera access for QR pairing and optional notification permission.
There is no Accessibility service, overlay permission, broad storage permission,
payment integration or background clipboard reader. The installed app does not
need ADB or the separate development test package.

## Revoke trust

There is no one-click key rotation in this preview. To revoke a lost or untrusted
device, quit LocalDrop on Windows, back up any data you need, remove its
`%LOCALAPPDATA%\app.localdrop.desktop\config.bin`, then restart and pair again.
Review existing queues before replacing Android's saved pairing. Removing this
configuration is deliberate revocation; do not do it during normal upgrades.

## Builds and releases

Windows preview binaries are unsigned. Android preview APKs are development-signed;
production signing and update delivery are not implemented. Obtain artifacts from
the project releases and verify their published SHA-256 checksums. Never commit
signing keys or use a development signing identity for a production release.

Only the current development line receives maintenance as project capacity allows.
