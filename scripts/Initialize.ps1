[CmdletBinding()]
param([int]$TimeoutSeconds = 240)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Java = Join-Path $Root 'runtime/java-25/bin/java.exe'
if (-not (Test-Path -LiteralPath $Java)) { throw 'Run scripts/Bootstrap.ps1 first.' }

foreach ($name in @('lobby','skybit','skyblock')) {
    $server = Join-Path $Root "servers/$name"
    Write-Host "[SkyBit] Initializing $name..." -ForegroundColor Cyan
    $logPath = Join-Path $server 'initialize.log'
    $writer = New-Object IO.StreamWriter($logPath, $false, (New-Object Text.UTF8Encoding($false)))
    $processInfo = New-Object Diagnostics.ProcessStartInfo
    $processInfo.FileName = $Java
    $processInfo.WorkingDirectory = $server
    $processInfo.Arguments = '-Xms512M -Xmx2G -jar server.jar --nogui'
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardInput = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $false
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $processInfo
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    try {
        [void]$process.Start()
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            $line = $process.StandardOutput.ReadLine()
            if ($null -eq $line) { Start-Sleep -Milliseconds 100; continue }
            $writer.WriteLine($line); $writer.Flush(); Write-Host $line
            if (-not $ready -and $line -match 'Done \(') {
                $ready = $true
                $settleSeconds = if ($name -eq 'skybit') { 25 } elseif ($name -eq 'skyblock') { 15 } else { 8 }
                Start-Sleep -Seconds $settleSeconds
                $process.StandardInput.WriteLine('save-all flush')
                Start-Sleep -Seconds 2
                $process.StandardInput.WriteLine('stop')
            }
        }
        if (-not $process.HasExited) { $process.Kill(); Write-Warning "$name exceeded the initialization timeout." }
        elseif ($process.ExitCode -ne 0) { Write-Warning "$name exited with code $($process.ExitCode); inspect initialize.log." }
        elseif (-not $ready) { Write-Warning "$name exited before reaching ready state." }
    } finally { $writer.Dispose(); $process.Dispose() }
}
& (Join-Path $PSScriptRoot 'Apply-Defaults.ps1')
