[CmdletBinding()]
param([switch]$Preview)

$managementDir = $PSScriptRoot
$compiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$framework = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
$source = Join-Path $managementDir 'SkyBitManagement.cs'
$output = Join-Path $managementDir 'SkyBit Management.exe'

if (-not (Test-Path -LiteralPath $compiler)) { throw 'C# compiler is unavailable.' }

& $compiler /nologo /target:winexe /optimize+ /platform:anycpu /out:$output `
    /reference:"$framework\WPF\PresentationCore.dll" `
    /reference:"$framework\WPF\PresentationFramework.dll" `
    /reference:"$framework\WPF\WindowsBase.dll" `
    /reference:"$framework\System.Xaml.dll" `
    /reference:"$framework\System.dll" `
    /reference:"$framework\System.Core.dll" `
    /reference:"$framework\System.Drawing.dll" `
    /reference:"$framework\System.IO.Compression.dll" `
    /reference:"$framework\System.IO.Compression.FileSystem.dll" `
    $source

if ($LASTEXITCODE -ne 0) { throw "SkyBit Management build failed with exit code $LASTEXITCODE." }
& $output --self-test
if ($LASTEXITCODE -ne 0) { throw "SkyBit Management self-test failed with exit code $LASTEXITCODE." }
Write-Host "Built: $output"
Write-Host "Self-test: OK"
if ($Preview) {
    $previewPath = Join-Path $managementDir 'preview.png'
    $previewProcess = Start-Process -FilePath $output -ArgumentList @('--preview', ('"' + $previewPath + '"')) -PassThru -Wait -WindowStyle Hidden
    if ($previewProcess.ExitCode -ne 0) { throw "SkyBit Management preview failed with exit code $($previewProcess.ExitCode)." }
    Write-Host "Preview: $previewPath"
}
