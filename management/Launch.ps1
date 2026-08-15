[CmdletBinding()]
param()

$managementDir = $PSScriptRoot
$application = Join-Path $managementDir 'SkyBit Management.exe'
if (-not (Test-Path -LiteralPath $application)) { throw 'SkyBit Management.exe is unavailable. Run Build.ps1 first.' }

Start-Process -FilePath $application -WorkingDirectory $managementDir
