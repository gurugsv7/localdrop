# LocalDrop / Transfer desk

Built-in ChatGPT image generation produced `localdrop-transfer-desk-mockup.png` as the Windows and Android design reference. The board is not a bitmap background inside either app. The implementation uses native fonts and vector line icons, without new runtime dependencies.

## Generation prompt / final visual brief

Use case: ui-mockup. Two complete, straight-on Windows and Android LocalDrop screens on one landscape reference board. Compact transfer desk / precision instrument identity: graphite `#191c1b`, panels `#202522`, ivory `#eeeae0`, muted sage `#a5ada5`, burnt orange `#f7845c`. Native sans typography, 1px separators, 8px corners, geometric two-offset-corner-arrow mark. No gradients, glows, stock illustrations, fake statistics, accounts or avatars.

Windows: LocalDrop header, Overview / Preferences rail, "Across the desk." heading, paired laptop-to-phone dashed route, ivory clipboard strip and Ctrl+V keys, latest three receipt-style transfer rows, reverse clipboard send and tray status footer.

Android: "Within reach." heading, paired laptop route, orange "Send copied text" action, ivory text composer, "Choose files" / "Receive" buttons, outbox state, local/encrypted footer. Put advanced controls behind Preferences. Keep the home page focused on sending.

## Implementation rules

- Windows uses the existing Tauri webview with local HTML/CSS/JS and inline SVG symbols. Android uses native Views and a small Canvas icon view, without Compose or a webview.
- Screen density follows the board at 960 × 800 desktop and around 390 dp Android. Layouts scroll or reflow on smaller screens and with larger fonts.
- Green means paired/trusted, not a claim of live connectivity. Never invent timestamps or clipboard contents. The paste strip only claims a ready paste after an observed receive/copy action.
- Preserve pairing, protocols, storage and the compact native desktop notification. No new permissions or background services.
- QR is private and revealed only on demand. No credentials in design assets, screenshots or source archives.
- The raster reference adds no installed-app RAM cost. It is included in source for contributors.
- Physical-phone feedback refined secondary actions to a brighter graphite `#303a32` fill and sage `#899683` outline. Ivory labels are 9.84:1 against this fill; the outline is 5.01:1 against the surrounding panel. Shared native button primitives keep retry, notifications, pairing, file and storage controls visually consistent.
