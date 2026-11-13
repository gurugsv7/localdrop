# Run once in an elevated PowerShell AFTER putting LocalDrop.exe in its final location.
param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference='Stop'
$exe=(Resolve-Path -LiteralPath $Executable).Path
if([IO.Path]::GetFileName($exe) -ne 'LocalDrop.exe'){throw 'Select the final LocalDrop.exe executable.'}
New-NetFirewallRule -DisplayName 'LocalDrop encrypted transfers' -Direction Inbound -Action Allow -Profile Private -Program $exe -Protocol TCP -LocalPort 45871 -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName 'LocalDrop local discovery' -Direction Inbound -Action Allow -Profile Private -Program $exe -Protocol UDP -LocalPort 45872 -RemoteAddress LocalSubnet
