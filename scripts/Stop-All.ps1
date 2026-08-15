$Root = Split-Path -Parent $PSScriptRoot
$pidDir = Join-Path $Root 'runtime/pids'
foreach($name in @('velocity','skyblock','skybit','lobby')) {
    $file = Join-Path $pidDir "$name.pid"
    if (-not (Test-Path -LiteralPath $file)) { continue }
    $processId = [int](Get-Content -Raw -LiteralPath $file)
    $proc = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -ne $proc) { Stop-Process -Id $processId; Write-Host "Stopped $name (PID $processId)" }
    Remove-Item -LiteralPath $file -Force
}
