# Third-party software

LocalDrop's original source is distributed under the [MIT license](LICENSE).
Dependencies retain their own licenses; LocalDrop's license does not replace them.

Rust dependency versions are pinned in `Cargo.lock`. Their license metadata and
source can be inspected with `cargo metadata --locked --format-version 1` and in
the corresponding crates.io packages. Direct dependencies include Tokio, Snow,
Serde, SHA-2, JNI, Tauri, clipboard-win, image, qrcode, and windows-sys.

The Android QR decoder is ZXing Core 3.5.3, distributed under Apache-2.0. The
Gradle wrapper is generated from Gradle 8.11.1, also distributed under Apache-2.0.

Windows packages may include Microsoft's WebView2 loader. This component and the
WebView2 runtime retain Microsoft's distribution terms. LLVM-MinGW builds also
include `libunwind.dll` and the toolchain's accompanying `LLVM-MinGW-LICENSE.txt`.
The standard MSVC build does not require that LLVM runtime DLL.

Redistributors should review the exact dependency and toolchain licenses for
their builds and include applicable notices. Release binaries are separate from
the source repository; no signing keys, toolchain bundles or downloaded SDKs are
checked into Git.
