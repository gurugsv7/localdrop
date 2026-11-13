# Build from source

The supported desktop target is Windows x64. Android preview packages are ARM64
and require Android 8.0 or newer. The Rust core can be tested independently.

## Windows

Install Rust stable using rustup, Visual Studio 2022 C++ Build Tools with the
Windows SDK, and the Microsoft Edge WebView2 Runtime. Then run from the repository:

```powershell
./scripts/build-windows.ps1
```

The script runs Rust tests, builds release binaries and stages the application in
`dist/LocalDrop-Windows/`. Keep `LocalDrop.exe` and `WebView2Loader.dll` together.
Run the executable from a permanent folder before configuring its firewall rules.
`CARGO_TARGET_DIR` is supported. Executable and WebView2 SDK locations come from
Cargo's structured build messages, so both legacy and newer build-directory
layouts are supported.
Local builds default to two Cargo workers; `-Jobs 4` opts into more parallelism
on a suitable machine. Hosted Windows CI uses four workers.

An alternative toolchain is Rust's `stable-x86_64-pc-windows-gnullvm` plus LLVM-MinGW.
Select that rustup toolchain, put its linker on PATH and set `LLVM_MINGW_HOME`.
The build script copies `libunwind.dll` and the LLVM-MinGW license for that build.
MSVC builds do not require `libunwind.dll`.

To exercise real Windows clipboard formats explicitly:

```powershell
./scripts/build-windows.ps1 -CheckClipboard
```

This check temporarily changes the system clipboard and attempts to restore
readable text, files and image formats. App-specific custom formats cannot be
restored. Do not run it while a paste/upload is in progress.

## Android from Windows

Install JDK 17, Android SDK platform 35 and build-tools 35.0.0, Android NDK r27 or
newer, and Rust stable. Set your local paths:

```powershell
$env:JAVA_HOME='C:\path\to\jdk-17'
$env:ANDROID_HOME='C:\path\to\Android\Sdk'
$env:ANDROID_NDK_HOME="$env:ANDROID_HOME\ndk\27.2.12479018"
./scripts/build-android.ps1
```

The script builds the Rust JNI library for `aarch64-linux-android`, stages it in
the app's JNI directory, then uses the checked-in Gradle 8.11.1 wrapper to compile
and lint the APK. The wrapper distribution has a pinned SHA-256 checksum.
The result is `dist/LocalDrop-android-arm64-debug.apk`.

A Gradle-only build deliberately fails if the Rust `.so` has not been staged.
The Android linker uses 16 KiB maximum page alignment. Other ABIs need explicit
Rust targets, NDK linkers and Gradle ABI configuration; they are not packaged here.

The first wrapper run downloads Gradle. Later builds reuse your Gradle cache.
Keep signing keys outside the repository. Debug APKs are for development; use
your own signing identity and distribution process for production.

## Checks

```sh
cargo fmt --all -- --check
cargo test --locked -p localdrop-core -p localdrop-android -j 2
```

The desktop package is Windows-specific. On other hosts, target the core/JNI
crates explicitly rather than building the entire workspace.

GitHub Actions runs core tests, a Windows package build, and Android compilation
and lint. Hardware checks are separate: see [VALIDATION.md](../VALIDATION.md).

## Optional firewall setup

Run an elevated PowerShell only after putting the Windows executable in its
permanent location:

```powershell
./scripts/firewall.ps1 -Executable 'C:\Apps\LocalDrop\LocalDrop.exe'
```

The script adds Private-profile, local-subnet inbound rules for TCP 45871 and UDP
45872, scoped to that executable. It does not change the network profile or create
Public-profile access. Review the script and use a trusted local network.
