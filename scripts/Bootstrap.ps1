[CmdletBinding()]
param(
    [switch]$SkipDownloads,
    [switch]$SkipBuild,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Runtime = Join-Path $Root 'runtime'
$ManifestPath = Join-Path $Root 'manifests/plugins.json'
$UserAgent = 'SkyBitNetworkBootstrap/1.0 (https://example.invalid/skybit)'
$Headers = @{ 'User-Agent' = $UserAgent }

function Write-Step([string]$Message) {
    Write-Host "[SkyBit] $Message" -ForegroundColor Cyan
}

function Get-EnvMap {
    $defaults = @{
        NETWORK_NAME = 'SkyBit'; PUBLIC_HOST = 'play.example.sk'; PROXY_PORT = '25565'
        LOBBY_PORT = '25566'; SKYBIT_PORT = '25567'; SKYBLOCK_PORT = '25568'
        PLAYER_LIMIT = '100'; DB_HOST = '127.0.0.1'; DB_PORT = '3306'
        DB_NAME = 'skybit'; DB_USER = 'skybit'; DB_PASSWORD = 'CHANGE_ME'
    }
    $envFile = Join-Path $Root 'shared/network.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match '^\s*([^#=]+)=(.*)$') { $defaults[$matches[1].Trim()] = $matches[2].Trim() }
        }
    }
    if (-not $defaults.ContainsKey('VELOCITY_SECRET') -or $defaults.VELOCITY_SECRET -like 'CHANGE_ME*') {
        $bytes = New-Object byte[] 48
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $defaults.VELOCITY_SECRET = [Convert]::ToBase64String($bytes)
    }
    return $defaults
}

function Render-Template([string]$Source, [string]$Destination, [hashtable]$Values) {
    if ((Test-Path -LiteralPath $Destination) -and -not $Force) { return }
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $Source
    foreach ($entry in $Values.GetEnumerator()) { $text = $text.Replace('${' + $entry.Key + '}', [string]$entry.Value) }
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Destination, $text, $utf8NoBom)
}

function Install-Java {
    $javaExe = Join-Path $Runtime 'java-25/bin/java.exe'
    if (Test-Path -LiteralPath $javaExe) { return $javaExe }
    Write-Step 'Downloading a workspace-local Eclipse Temurin Java 25 runtime'
    New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
    $api = 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
    $asset = (Invoke-RestMethod -Headers $Headers -Uri $api | Select-Object -First 1)
    if ($null -eq $asset.binary.package.link) { throw 'Adoptium did not return a Java 25 package.' }
    $zip = Join-Path $Runtime 'java-25.zip'
    Invoke-WebRequest -Headers $Headers -Uri $asset.binary.package.link -OutFile $zip
    $extract = Join-Path $Runtime 'java-25-extract'
    Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force
    $jdk = Get-ChildItem -LiteralPath $extract -Directory | Select-Object -First 1
    Move-Item -LiteralPath $jdk.FullName -Destination (Join-Path $Runtime 'java-25')
    Remove-Item -LiteralPath $zip -Force
    Remove-Item -LiteralPath $extract -Force -Recurse
    return $javaExe
}

function Get-PaperDownload([string]$Project, [string]$Version) {
    $url = "https://fill.papermc.io/v3/projects/$Project/versions/$Version/builds"
    $builds = Invoke-RestMethod -Headers $Headers -Uri $url
    $build = $builds | Where-Object channel -eq 'STABLE' | Select-Object -First 1
    if ($null -eq $build) { $build = $builds | Where-Object channel -eq 'RECOMMENDED' | Select-Object -First 1 }
    if ($null -eq $build) { throw "No stable $Project build for $Version." }
    return $build.downloads.'server:default'.url
}

function Get-LatestVelocityDownload {
    $project = Invoke-RestMethod -Headers $Headers -Uri 'https://fill.papermc.io/v3/projects/velocity'
    $versions = @($project.versions.PSObject.Properties.Value | ForEach-Object { $_ })
    foreach ($version in $versions) {
        try {
            $builds = Invoke-RestMethod -Headers $Headers -Uri "https://fill.papermc.io/v3/projects/velocity/versions/$version/builds"
            $build = $builds | Where-Object channel -eq 'STABLE' | Select-Object -First 1
            if ($null -ne $build) { return $build.downloads.'server:default'.url }
        } catch { continue }
    }
    throw 'No stable Velocity build was found.'
}

function Get-ModrinthVersion([string]$ProjectId, [string]$Loader, [string]$GameVersion) {
    $versions = Invoke-RestMethod -Headers $Headers -Uri "https://api.modrinth.com/v2/project/$ProjectId/version"
    $matches = @($versions | Where-Object {
        $_.version_type -eq 'release' -and $_.loaders -contains $Loader -and
        ($Loader -eq 'velocity' -or $_.game_versions -contains $GameVersion)
    })
    if ($matches.Count -eq 0 -and $Loader -eq 'paper') {
        $matches = @($versions | Where-Object {
            $_.version_type -eq 'release' -and
            ($_.loaders -contains 'bukkit' -or $_.loaders -contains 'spigot') -and
            $_.game_versions -contains $GameVersion
        })
    }
    return $matches | Select-Object -First 1
}

function Install-ModrinthPlugin($Plugin, [string]$Loader, [string]$ServerRoot, [string]$GameVersion) {
    $version = Get-ModrinthVersion -ProjectId $Plugin.id -Loader $Loader -GameVersion $GameVersion
    if ($null -eq $version) {
        if ($Plugin.optional) { Write-Warning "Optional plugin $($Plugin.name) has no compatible release; skipped."; return }
        throw "No compatible $Loader/$GameVersion release found for $($Plugin.name) ($($Plugin.id))."
    }
    $file = $version.files | Where-Object primary | Select-Object -First 1
    if ($null -eq $file) { $file = $version.files | Select-Object -First 1 }
    $relative = if ($Plugin.destination) { [string]$Plugin.destination } else { 'plugins' }
    $destinationDir = Join-Path $ServerRoot $relative
    New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
    $destination = Join-Path $destinationDir $file.filename
    if ((Test-Path -LiteralPath $destination) -and -not $Force) { return }
    Write-Step "Downloading $($Plugin.name) $($version.version_number)"
    Invoke-WebRequest -Headers $Headers -Uri $file.url -OutFile $destination
    if ($file.hashes.sha512) {
        $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actual -ne $file.hashes.sha512.ToLowerInvariant()) { throw "Checksum mismatch for $($Plugin.name)." }
    }
}

function Install-DirectPlugin($Plugin, [string]$ServerRoot) {
    $relative = if ($Plugin.destination) { [string]$Plugin.destination } else { 'plugins' }
    $destinationDir = Join-Path $ServerRoot $relative
    New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
    $destination = Join-Path $destinationDir ([string]$Plugin.filename)
    if ((Test-Path -LiteralPath $destination) -and -not $Force) { return }
    Write-Step "Downloading $($Plugin.name)"
    Invoke-WebRequest -Headers $Headers -Uri ([string]$Plugin.url) -OutFile $destination
    if ($Plugin.sha512) {
        $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actual -ne ([string]$Plugin.sha512).ToLowerInvariant()) { throw "Checksum mismatch for $($Plugin.name)." }
    }
}

function Install-PluginSet($Plugins, [string]$Loader, [string]$ServerRoot, [string]$GameVersion) {
    foreach ($plugin in $Plugins) {
        if ($plugin.url) { Install-DirectPlugin $plugin $ServerRoot }
        else { Install-ModrinthPlugin $plugin $Loader $ServerRoot $GameVersion }
    }
}

$values = Get-EnvMap
$envOutput = ($values.GetEnumerator() | Sort-Object Key | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join "`n"
$utf8NoBom = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $Root 'shared/network.env'), $envOutput + "`n", $utf8NoBom)
Set-Content -LiteralPath (Join-Path $Root 'proxy/forwarding.secret') -Value $values.VELOCITY_SECRET -Encoding ascii -NoNewline
Render-Template (Join-Path $Root 'proxy/velocity.toml.template') (Join-Path $Root 'proxy/velocity.toml') $values
$miniMotdRoot = Join-Path $Root 'proxy/plugins/minimotd-velocity'
New-Item -ItemType Directory -Force -Path (Join-Path $miniMotdRoot 'icons') | Out-Null
Render-Template (Join-Path $Root 'proxy/minimotd-main.conf.template') (Join-Path $miniMotdRoot 'main.conf') $values
$serverIcon = Join-Path $Root 'assets/skybit-server-icon.png'
if (Test-Path -LiteralPath $serverIcon) {
    Copy-Item -LiteralPath $serverIcon -Destination (Join-Path $miniMotdRoot 'icons/skybit.png') -Force
    Copy-Item -LiteralPath $serverIcon -Destination (Join-Path $Root 'proxy/server-icon.png') -Force
}
foreach ($name in @('lobby', 'skybit', 'skyblock')) {
    $serverRoot = Join-Path $Root "servers/$name"
    Render-Template (Join-Path $serverRoot 'server.properties.template') (Join-Path $serverRoot 'server.properties') $values
    Set-Content -LiteralPath (Join-Path $serverRoot 'eula.txt') -Value 'eula=true' -Encoding ascii
    New-Item -ItemType Directory -Force -Path (Join-Path $serverRoot 'plugins') | Out-Null
}

if (-not $SkipDownloads) {
    $java = Install-Java
    $manifest = Get-Content -Raw -LiteralPath $ManifestPath | ConvertFrom-Json
    $gameVersion = $manifest.minecraftVersion
    foreach ($name in @('lobby', 'skybit', 'skyblock')) {
        $serverRoot = Join-Path $Root "servers/$name"
        $jar = Join-Path $serverRoot 'server.jar'
        if (-not (Test-Path -LiteralPath $jar) -or $Force) {
            Write-Step "Downloading Paper $gameVersion for $name"
            Invoke-WebRequest -Headers $Headers -Uri (Get-PaperDownload 'paper' $gameVersion) -OutFile $jar
        }
        Install-PluginSet $manifest.paper.common 'paper' $serverRoot $gameVersion
        Install-PluginSet $manifest.paper.$name 'paper' $serverRoot $gameVersion
    }
    $velocityJar = Join-Path $Root 'proxy/velocity.jar'
    if (-not (Test-Path -LiteralPath $velocityJar) -or $Force) {
        Write-Step 'Downloading Velocity'
        Invoke-WebRequest -Headers $Headers -Uri (Get-LatestVelocityDownload) -OutFile $velocityJar
    }
    Install-PluginSet $manifest.velocity 'velocity' (Join-Path $Root 'proxy') $gameVersion
}

if (-not $SkipBuild) {
    $java = Install-Java
    $env:JAVA_HOME = Join-Path $Runtime 'java-25'
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
    $gradleDir = Join-Path $Runtime 'gradle-9.6.1'
    if (-not (Test-Path -LiteralPath (Join-Path $gradleDir 'bin/gradle.bat'))) {
        Write-Step 'Downloading workspace-local Gradle'
        $gradleZip = Join-Path $Runtime 'gradle.zip'
        Invoke-WebRequest -Headers $Headers -Uri 'https://services.gradle.org/distributions/gradle-9.6.1-bin.zip' -OutFile $gradleZip
        Expand-Archive -LiteralPath $gradleZip -DestinationPath $Runtime -Force
        Remove-Item -LiteralPath $gradleZip -Force
    }
    Write-Step 'Building SkyBitCore'
    & (Join-Path $gradleDir 'bin/gradle.bat') -p (Join-Path $Root 'plugins-src/SkyBitCore') clean build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'SkyBitCore build failed.' }
    $built = Get-ChildItem -LiteralPath (Join-Path $Root 'plugins-src/SkyBitCore/build/libs') -Filter 'SkyBitCore-*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $built.FullName -Destination (Join-Path $Root 'servers/skybit/plugins/SkyBitCore.jar') -Force

    Write-Step 'Building SkyBitLobby'
    & (Join-Path $gradleDir 'bin/gradle.bat') -p (Join-Path $Root 'plugins-src/SkyBitLobby') clean build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'SkyBitLobby build failed.' }
    $builtLobby = Get-ChildItem -LiteralPath (Join-Path $Root 'plugins-src/SkyBitLobby/build/libs') -Filter 'SkyBitLobby-*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $builtLobby.FullName -Destination (Join-Path $Root 'servers/lobby/plugins/SkyBitLobby.jar') -Force

    Write-Step 'Building SkyBitSpawn'
    & (Join-Path $gradleDir 'bin/gradle.bat') -p (Join-Path $Root 'plugins-src/SkyBitSpawn') clean build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'SkyBitSpawn build failed.' }
    $builtSpawn = Get-ChildItem -LiteralPath (Join-Path $Root 'plugins-src/SkyBitSpawn/build/libs') -Filter 'SkyBitSpawn-*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $builtSpawn.FullName -Destination (Join-Path $Root 'servers/skybit/plugins/SkyBitSpawn.jar') -Force
    Copy-Item -LiteralPath $builtSpawn.FullName -Destination (Join-Path $Root 'servers/skyblock/plugins/SkyBitSpawn.jar') -Force

    Write-Step 'Building SkyBitAuth'
    & (Join-Path $gradleDir 'bin/gradle.bat') -p (Join-Path $Root 'plugins-src/SkyBitAuth') clean build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'SkyBitAuth build failed.' }
    $builtAuth = Get-ChildItem -LiteralPath (Join-Path $Root 'plugins-src/SkyBitAuth/build/libs') -Filter 'SkyBitAuth-*.jar' | Select-Object -First 1
    Copy-Item -LiteralPath $builtAuth.FullName -Destination (Join-Path $Root 'proxy/plugins/SkyBitAuth.jar') -Force
}

Write-Step 'Bootstrap complete. Review shared/network.env, then run scripts/Initialize.ps1.'
