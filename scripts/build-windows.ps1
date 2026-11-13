param([switch]$CheckClipboard,[string]$LlvmMingw=$env:LLVM_MINGW_HOME)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
cargo test --locked --release --manifest-path "$repo\Cargo.toml" -p localdrop-core -p localdrop-android -p localdrop-desktop -j 2
if($LASTEXITCODE){throw 'Rust tests failed'}
cargo build --locked --manifest-path "$repo\Cargo.toml" -p localdrop-desktop --release --bins -j 2
if($LASTEXITCODE){throw 'Windows build failed. Install Visual Studio C++ Build Tools and Windows SDK, or configure LLVM-MinGW.'}
$metadata=cargo metadata --locked --manifest-path "$repo\Cargo.toml" --format-version 1 --no-deps | ConvertFrom-Json
if($LASTEXITCODE){throw 'Could not locate Cargo build outputs'}
$release=Join-Path $metadata.target_directory 'release'
$bundle=Join-Path $repo 'dist\LocalDrop-Windows'
New-Item -ItemType Directory -Force $bundle | Out-Null
$loader=Get-ChildItem -LiteralPath "$release\build" -Directory -Filter 'webview2-com-sys-*' |
    ForEach-Object {Join-Path $_.FullName 'out\x64\WebView2Loader.dll'} |
    Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
if(!$loader){throw 'WebView2Loader.dll not found in compiled dependencies'}
Copy-Item -LiteralPath $loader -Destination $release
Copy-Item -LiteralPath $loader -Destination $bundle
if((rustc -vV | Out-String) -match 'host: x86_64-pc-windows-gnullvm'){
    if(!$LlvmMingw){throw 'Set LLVM_MINGW_HOME when distributing the LLVM Windows build.'}
    $unwind=Join-Path $LlvmMingw 'x86_64-w64-mingw32\bin\libunwind.dll'
    Copy-Item -LiteralPath $unwind -Destination $release
    Copy-Item -LiteralPath $unwind -Destination $bundle
    Copy-Item -LiteralPath (Join-Path $LlvmMingw 'LICENSE.TXT') -Destination "$bundle\LLVM-MinGW-LICENSE.txt"
}
if($CheckClipboard){
    & "$release\clipboard-check.exe"
    if($LASTEXITCODE){throw 'Windows clipboard test failed'}
}
Copy-Item -LiteralPath "$release\localdrop-desktop.exe" -Destination "$bundle\LocalDrop.exe"
Copy-Item -LiteralPath "$repo\LICENSE","$repo\THIRD_PARTY_NOTICES.md" -Destination $bundle
Write-Output "Windows package: $bundle"
