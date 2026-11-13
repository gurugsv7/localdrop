param([string]$Ndk=$env:ANDROID_NDK_HOME,[string]$Gradle='')
$ErrorActionPreference='Stop'
if(!$Ndk -or !(Test-Path -LiteralPath $Ndk)){throw 'Set ANDROID_NDK_HOME to an installed Android NDK (r27 or newer).'}
if(!$env:ANDROID_HOME){throw 'Set ANDROID_HOME to an Android SDK with platform/build-tools 35.'}
$repo=Split-Path -Parent $PSScriptRoot
if(!$Gradle){$Gradle=Join-Path $repo 'android\gradlew.bat'}
$bin=Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=Join-Path $bin 'aarch64-linux-android26-clang.cmd'
$env:CC_aarch64_linux_android=$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$env:AR_aarch64_linux_android=Join-Path $bin 'llvm-ar.exe'
rustup target add aarch64-linux-android
if($LASTEXITCODE){throw 'Rust Android target installation failed'}
cargo build --locked --manifest-path "$repo\Cargo.toml" -p localdrop-android --release --target aarch64-linux-android -j 2
if($LASTEXITCODE){throw 'Android native build failed'}
$metadata=cargo metadata --locked --manifest-path "$repo\Cargo.toml" --format-version 1 --no-deps | ConvertFrom-Json
if($LASTEXITCODE){throw 'Could not locate Cargo build outputs'}
$jni=Join-Path $repo 'android\app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force $jni | Out-Null
Copy-Item -LiteralPath (Join-Path $metadata.target_directory 'aarch64-linux-android\release\liblocaldrop_android.so') -Destination $jni
& $Gradle -p "$repo\android" :app:assembleDebug :app:lintDebug --no-daemon --console=plain
if($LASTEXITCODE){throw 'APK build or lint failed'}
$dist=Join-Path $repo 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item -LiteralPath "$repo\android\app\build\outputs\apk\debug\app-debug.apk" -Destination "$dist\LocalDrop-android-arm64-debug.apk"
Write-Output "Android APK: $dist\LocalDrop-android-arm64-debug.apk"
