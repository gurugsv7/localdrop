param([switch]$CheckClipboard,[string]$LlvmMingw=$env:LLVM_MINGW_HOME,[ValidateRange(1,16)][int]$Jobs=2)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
cargo test --locked --release --manifest-path "$repo\Cargo.toml" -p localdrop-core -p localdrop-android -p localdrop-desktop -j $Jobs
if($LASTEXITCODE){throw 'Rust tests failed'}
$buildOutput=@(cargo build --locked --manifest-path "$repo\Cargo.toml" -p localdrop-desktop --release --bins -j $Jobs --message-format=json-render-diagnostics)
$buildExit=$LASTEXITCODE
$messages=@($buildOutput | ForEach-Object {$_ | ConvertFrom-Json})
foreach($message in $messages){
    if($message.reason -eq 'compiler-message' -and $message.message.rendered){[Console]::Error.WriteLine($message.message.rendered)}
}
if($buildExit){throw 'Windows build failed. Install Visual Studio C++ Build Tools and Windows SDK, or configure LLVM-MinGW.'}
$desktopBuild=$messages | Where-Object {$_.reason -eq 'compiler-artifact' -and $_.target.name -eq 'localdrop-desktop' -and $_.executable} | Select-Object -First 1
$clipboardBuild=$messages | Where-Object {$_.reason -eq 'compiler-artifact' -and $_.target.name -eq 'clipboard-check' -and $_.executable} | Select-Object -First 1
$webviewBuild=$messages | Where-Object {$_.reason -eq 'build-script-executed' -and $_.package_id -like '*webview2-com-sys*'} | Select-Object -First 1
if(!$desktopBuild -or !$webviewBuild){throw 'Cargo did not report the required executable and WebView2 build outputs'}
$release=Split-Path -Parent $desktopBuild.executable
$bundle=Join-Path $repo 'dist\LocalDrop-Windows'
New-Item -ItemType Directory -Force $bundle | Out-Null
$loader=Join-Path $webviewBuild.out_dir 'x64\WebView2Loader.dll'
if(!(Test-Path -LiteralPath $loader)){throw 'WebView2Loader.dll missing from the build output reported by Cargo'}
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
    if(!$clipboardBuild){throw 'Cargo did not report the clipboard checker executable'}
    & $clipboardBuild.executable
    if($LASTEXITCODE){throw 'Windows clipboard test failed'}
}
Copy-Item -LiteralPath $desktopBuild.executable -Destination "$bundle\LocalDrop.exe"
Copy-Item -LiteralPath "$repo\LICENSE","$repo\THIRD_PARTY_NOTICES.md" -Destination $bundle
Write-Output "Windows package: $bundle"
