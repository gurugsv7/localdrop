# Run once in an elevated PowerShell AFTER putting LocalDrop.exe in its final location.
# Windows marks newly joined Wi-Fi networks and phone hotspots as Public, so the rules
# cover Private and Public profiles. They stay scoped to this executable, the two
# LocalDrop ports and the local subnet; every connection must also pass the Noise
# handshake with the pairing key. Pass -PrivateOnly to keep the stricter old behaviour.
param([Parameter(Mandatory=$true)][string]$Executable,[switch]$PrivateOnly)
$ErrorActionPreference='Stop'
$exe=(Resolve-Path -LiteralPath $Executable).Path
if([IO.Path]::GetFileName($exe) -ne 'LocalDrop.exe'){throw 'Select the final LocalDrop.exe executable.'}
$profiles=if($PrivateOnly){'Private'}else{'Private','Public'}
# Replace every earlier rule for this executable, including Windows' own allow/block prompt
# rules and rules pinned to one remote IP, which break when either device changes address.
Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue|Get-NetFirewallRule|Remove-NetFirewallRule
foreach($name in 'LocalDrop encrypted transfers','LocalDrop local discovery'){Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue|Remove-NetFirewallRule}
New-NetFirewallRule -DisplayName 'LocalDrop encrypted transfers' -Direction Inbound -Action Allow -Profile $profiles -Program $exe -Protocol TCP -LocalPort 45871 -RemoteAddress LocalSubnet
New-NetFirewallRule -DisplayName 'LocalDrop local discovery' -Direction Inbound -Action Allow -Profile $profiles -Program $exe -Protocol UDP -LocalPort 45872 -RemoteAddress LocalSubnet
