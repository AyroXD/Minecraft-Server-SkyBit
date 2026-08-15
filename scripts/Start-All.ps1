[CmdletBinding()]
param()
$Root = Split-Path -Parent $PSScriptRoot
$Java = Join-Path $Root 'runtime/java-25/bin/java.exe'
if (-not (Test-Path -LiteralPath $Java)) { throw 'Run scripts/Bootstrap.ps1 first.' }
$services = @(
    @{ Name='lobby'; Path='servers/lobby'; Min='1G'; Max='2G'; Jar='server.jar'; NoGui=$true },
    @{ Name='skybit'; Path='servers/skybit'; Min='2G'; Max='6G'; Jar='server.jar'; NoGui=$true },
    @{ Name='skyblock'; Path='servers/skyblock'; Min='2G'; Max='4G'; Jar='server.jar'; NoGui=$true },
    @{ Name='velocity'; Path='proxy'; Min='256M'; Max='1G'; Jar='velocity.jar'; NoGui=$false }
)
New-Item -ItemType Directory -Force -Path (Join-Path $Root 'runtime/pids') | Out-Null
foreach($service in $services) {
    $dir = Join-Path $Root $service.Path
    $log = Join-Path $dir 'console.log'
    $err = Join-Path $dir 'console-error.log'
    $arguments = @("-Xms$($service.Min)","-Xmx$($service.Max)",'-XX:+UseG1GC','-jar',$service.Jar)
    if ($service.NoGui) { $arguments += '--nogui' }
    $proc = Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $dir -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err
    Set-Content -LiteralPath (Join-Path $Root "runtime/pids/$($service.Name).pid") -Value $proc.Id -Encoding ascii
    Write-Host "Started $($service.Name) (PID $($proc.Id))"
}
