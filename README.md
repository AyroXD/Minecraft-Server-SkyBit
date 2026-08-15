# SkyBit Network

Hotový reprodukovateľný základ CZ/SK Minecraft siete:

```text
Velocity :25565
├── Lobby     :25566
├── SkyBit    :25567
└── SkyBlock  :25568
```

Cieľová verzia je **Paper 1.21.11 / Java 25**. Java 25 je potrebná pre aktuálny BentoBox a FancyNpcs build. ViaVersion umožňuje pripojenie novších klientov. Bootstrap kontroluje Modrinth SHA-512 a vyberá SlimefunCore build výslovne označený pre Paper 1.21.11.

## Čo je implementované

- Velocity sieť s modern player forwarding a backendmi viazanými na `127.0.0.1`
- offline/w⁠arez prístup chránený vlastným `SkyBitAuth` proxy pluginom; heslá sú uložené iba ako salted PBKDF2-HMAC-SHA256 hashe
- neónové dvojriadkové MiniMOTD, vlastná SkyBit ikona a limit 100 hráčov
- chránená plávajúca SkyBit lobby mapa s void generátorom, hologramami a funkčnými portálmi
- SkyBit Slimefun Survival a BentoBox/BSkyBlock
- LuckPerms, VaultUnlocked + EssentialsX economy, PlaceholderAPI, TAB
- GriefPrevention3D claims, Chunky, WorldGuard, CoreProtect a GrimAC
- GUI server shop, auction house, EconomyShop chest shopy a Jobs-Plus
- Quests, crates, custom fishing, EliteMobs, NPC a hologram stack
- udržiavaný open-source `SlimefunCore` fork pre 1.21.11 s viac než 500 predmetmi, strojmi, energiou a výskumom
- `SkyBitCore`: level 1–100, daily/weekly questy, SkyCoins, `/coinshop`, daily streak, aktívny playtime, achievementy, eventy, custom itemy a leaderboard placeholdery

## Spustenie

V PowerShelli v tomto priečinku:

```powershell
.\scripts\Bootstrap.ps1
.\scripts\Initialize.ps1
.\scripts\Validate.ps1
.\scripts\Start-All.ps1
```

Pred verejným spustením uprav `shared/network.env`, najmä `PUBLIC_HOST` a databázové údaje. Bootstrap už vytvoril náhodný Velocity forwarding secret. Backend porty **neotváraj vo firewalle ani hostingu**; verejný je iba port 25565.

Ďalšie kroky sú v [docs/ADMIN-SETUP.md](docs/ADMIN-SETUP.md) a prevádzka v [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Užitočné príkazy

Hráč:

- `/rtp`, `/bal`, `/pay`, `/baltop`, `/shop`, `/ah`, `/jobs`
- `/quests`, `/daily`, `/level`, `/coinshop`, `/coins`, `/sf guide`
- pri prvom pripojení `/register <heslo> <heslo>`, potom `/login <heslo>`; zmena cez `/changepassword <staré> <nové>`
- `/claim`, `/trust`, `/containertrust`, `/accesstrust`, `/unclaim`
- `/emf shop`, `/emf top`, `/island` na SkyBlocku

Admin SkyBitCore:

```text
/sbadmin addxp <hráč> <xp>
/sbadmin coins <hráč> <počet>
/sbadmin event <FISHING|MINING|MOB_HUNT|FARMING>
/sbadmin giveitem <hráč> <miners_pickaxe|farmers_hoe|fishermans_rod>
```
