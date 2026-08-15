$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Read-EnvFile([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#=]+)=(.*)$') { $result[$matches[1].Trim()] = $matches[2].Trim() }
    }
    return $result
}

function Replace-Setting([string]$Path, [string]$Pattern, [string]$Replacement) {
    if (-not (Test-Path -LiteralPath $Path)) { Write-Warning "Missing $Path"; return }
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    $updated = [regex]::Replace($text, $Pattern, $Replacement)
    if ($updated -eq $text) { return }
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $updated, $utf8NoBom)
}

$envMap = Read-EnvFile (Join-Path $Root 'shared/network.env')
foreach ($name in @('lobby','skybit','skyblock')) {
    $server = Join-Path $Root "servers/$name"
    $paper = Join-Path $server 'config/paper-global.yml'
    if (Test-Path -LiteralPath $paper) {
        $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $paper
        $velocity = [regex]::Match($text, '(?ms)^  velocity:\s*\r?\n.*?(?=^  \S|\z)')
        if ($velocity.Success) {
            $block = $velocity.Value
            $block = [regex]::Replace($block, '(?m)^    enabled: .*$', '    enabled: true')
            $block = [regex]::Replace($block, '(?m)^    online-mode: .*$', '    online-mode: false')
            $secret = [string]$envMap.VELOCITY_SECRET
            $block = [regex]::Replace($block, '(?m)^    secret: .*$', { param($m) '    secret: ' + $secret })
            $text = $text.Substring(0, $velocity.Index) + $block + $text.Substring($velocity.Index + $velocity.Length)
            $utf8NoBom = New-Object Text.UTF8Encoding($false)
            [IO.File]::WriteAllText($paper, $text, $utf8NoBom)
        }
    }
    Replace-Setting (Join-Path $server 'bukkit.yml') '(?m)^  connection-throttle: .*' '  connection-throttle: -1'
}

# Lobby is a single protected void world; do not create unused Nether dimensions.
Replace-Setting (Join-Path $Root 'servers/lobby/config/paper-global.yml') '(?m)^  enable-nether: .*' '  enable-nether: false'

$essentials = Join-Path $Root 'servers/skybit/plugins/Essentials/config.yml'
Replace-Setting $essentials '(?m)^starting-balance: .*' 'starting-balance: 500.0'
Replace-Setting $essentials '(?m)^auto-afk: .*' 'auto-afk: 300'
Replace-Setting $essentials '(?m)^freeze-afk-players: .*' 'freeze-afk-players: true'
Replace-Setting $essentials '(?m)^cancel-afk-on-interact: .*' 'cancel-afk-on-interact: true'
if (Test-Path -LiteralPath $essentials) {
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $essentials
    $text = [regex]::Replace($text, '(?ms)^sethome-multiple:\r?\n(?:  .*\r?\n)+', "sethome-multiple:`r`n  default: 3`r`n  vip: 5`r`n  vipplus: 7`r`n  mvp: 10`r`n")
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($essentials, $text, $utf8NoBom)
}

$grief = Join-Path $Root 'servers/skybit/plugins/GriefPreventionData/config.yml'
Replace-Setting $grief '(?m)^    InitialBlocks: .*' '    InitialBlocks: 500'
Replace-Setting $grief '(?m)^(    Claim Blocks Accrued Per Hour:\r?\n)      Default: .*' "`$1      Default: 200"
Replace-Setting $grief '(?m)^(    Max Accrued Claim Blocks:\r?\n)      Default: .*' "`$1      Default: 20000"
Replace-Setting $grief '(?m)^    Accrued Idle Threshold: .*' '    Accrued Idle Threshold: 5'

$economyShop = Join-Path $Root 'servers/skybit/plugins/EconomyShop/config.yml'
Replace-Setting $economyShop '(?m)^  sales-tax: false\s*$' '  sales-tax: true'
Replace-Setting $economyShop '(?m)^  sales-tax: 0\.0\s*$' '  sales-tax: 2.0'
Replace-Setting $economyShop '(?m)^  buy-sell-spread: .*' '  buy-sell-spread: 0.30'
Replace-Setting $economyShop '(?m)^  finite-min-multiplier: .*' '  finite-min-multiplier: 0.60'
Replace-Setting $economyShop '(?m)^  finite-max-multiplier: .*' '  finite-max-multiplier: 1.60'
Replace-Setting $economyShop '(?m)^  creation-cost: .*' '  creation-cost: 250.0'
Replace-Setting $economyShop '(?m)^  max-shops-per-player: .*' '  max-shops-per-player: 3'
Replace-Setting $economyShop '(?m)^  transaction-tax-percent: .*' '  transaction-tax-percent: 3.0'
Replace-Setting $economyShop '(?m)^  auctionhouse-integration: true\s*$' '  auctionhouse-integration: false'
# Disable only the dedicated AuctionHouse integration block; both plugins still work independently.
if (Test-Path -LiteralPath $economyShop) {
    $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $economyShop
    $block = [regex]::Match($text, '(?ms)^auctionhouse:\r?\n.*?(?=^\S|\z)')
    if ($block.Success) {
        $updatedBlock = [regex]::Replace($block.Value, '(?m)^  enabled: true$', '  enabled: false')
        $text = $text.Substring(0, $block.Index) + $updatedBlock + $text.Substring($block.Index + $block.Length)
        $utf8NoBom = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($economyShop, $text, $utf8NoBom)
    }
}

$auction = Join-Path $Root 'servers/skybit/plugins/AuctionHouse/config.yml'
Replace-Setting $auction '(?m)^tax: .*' 'tax: 0.03 # 3% money sink'
Replace-Setting $auction '(?m)^default-max-auctions: .*' 'default-max-auctions: 5'
Replace-Setting $auction '(?m)^bin-auction-duration: .*' 'bin-auction-duration: 172800 # 48 hours'
Replace-Setting $auction '(?m)^max-bin: .*' 'max-bin: 10000000'

$jobs = Join-Path $Root 'servers/skybit/plugins/JobsPlus/config.yml'
Replace-Setting $jobs '(?m)^  max-jobs: .*' '  max-jobs: 2'
Replace-Setting $jobs '(?m)^  max-actions-per-second: .*' '  max-actions-per-second: 15'
Replace-Setting $jobs '(?m)^  tax-percentage: .*' '  tax-percentage: 5'

Write-Host '[SkyBit] Safe defaults applied.' -ForegroundColor Green
