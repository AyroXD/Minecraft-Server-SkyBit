$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$errors = New-Object Collections.Generic.List[string]
$warnings = New-Object Collections.Generic.List[string]

foreach ($path in @('proxy/velocity.jar','proxy/velocity.toml','proxy/forwarding.secret','proxy/plugins/SkyBitAuth.jar','proxy/server-icon.png','servers/lobby/server.jar','servers/skybit/server.jar','servers/skyblock/server.jar','servers/skybit/plugins/SkyBitCore.jar','servers/skybit/plugins/SlimefunCore4-2708.jar','servers/lobby/plugins/SkyBitLobby.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Root $path))) { $errors.Add("Missing: $path") }
}
foreach ($name in @('lobby','skybit','skyblock')) {
    $plugins = @(Get-ChildItem -LiteralPath (Join-Path $Root "servers/$name/plugins") -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue)
    if ($plugins.Count -eq 0) { $errors.Add("No plugins installed for $name") }
    $latest = Join-Path $Root "servers/$name/logs/latest.log"
    if (Test-Path -LiteralPath $latest) {
        $bad = Select-String -LiteralPath $latest -Pattern '\[(?:[^\]]*/)?(?:ERROR|SEVERE)\]|Could not load|failed to load|(?:^|\s)[A-Za-z0-9_.$]+Exception(?::|\s)' -CaseSensitive:$false
        foreach ($line in $bad) { $warnings.Add("$name log line $($line.LineNumber): $($line.Line.Trim())") }
    } else { $warnings.Add("$name has not been initialized yet") }
}

$proxyLatest = Join-Path $Root 'proxy/logs/latest.log'
if (Test-Path -LiteralPath $proxyLatest) {
    $bad = Select-String -LiteralPath $proxyLatest -Pattern '\[(?:[^\]]*/)?(?:ERROR|SEVERE)\]|Could not load|failed to load|(?:^|\s)[A-Za-z0-9_.$]+Exception(?::|\s)' -CaseSensitive:$false
    foreach ($line in $bad) { $warnings.Add("proxy log line $($line.LineNumber): $($line.Line.Trim())") }
} else { $warnings.Add('proxy has not been initialized yet') }

Write-Host "Errors: $($errors.Count)  Warnings: $($warnings.Count)"
$errors | ForEach-Object { Write-Host "ERROR: $_" -ForegroundColor Red }
$warnings | Select-Object -First 40 | ForEach-Object { Write-Host "WARN: $_" -ForegroundColor Yellow }
if ($warnings.Count -gt 40) { Write-Host "... plus $($warnings.Count - 40) more warnings" }
if ($errors.Count -gt 0) { exit 1 }
