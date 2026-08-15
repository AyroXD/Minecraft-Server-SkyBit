# Prevádzka siete

## Bezpečnostný model

Velocity je jediný verejný vstup. `server-ip=127.0.0.1` na všetkých troch Paper serveroch bráni obídeniu proxy na jednom stroji. Paper backends používajú `online-mode=false`, preto nikdy nesmú byť vystavené internetu. Velocity je tiež v offline/w⁠arez režime a identity chráni vlastný `SkyBitAuth`: hráča nepustí na žiadny backend pred `/register` alebo `/login`, heslá ukladá ako salted PBKDF2 hashe a po piatich chybných pokusoch účet na 30 sekúnd zamkne. Modern forwarding so samostatným náhodným secretom zostáva zapnutý.

Runtime je workspace-local Temurin Java 25 v `runtime/java-25`; server nemení systémovú Java inštaláciu.

Pri rozdelení serverov na viac strojov:

1. backend IP zmeň z loopback na privátnu IP,
2. firewallom povoľ backend port iba z IP Velocity,
3. na každom backende ponechaj rovnaký forwarding secret,
4. nikdy nezverejňuj porty 25566–25568.

## Pamäť

Predvolené maximum v `Start-All.ps1`:

- Lobby 2 GB
- SkyBit Survival 6 GB
- SkyBlock 4 GB
- Velocity 1 GB

Po prvom týždni sleduj MSPT, heap a počet entít. Nezvyšuj slepo `view-distance`; pri survival svete najprv dokonči Chunky pregeneration.

## Pregeneration

Na SkyBit Survival spusti z konzoly alebo ako admin:

```text
chunky world world
chunky radius 10000
chunky start
```

Samostatne pregeneruj Nether a End podľa plánovaného world borderu. Pregeneration rob mimo špičky a pred otvorením servera.

## Zálohy

Zálohuj minimálne:

- celé world priečinky,
- `plugins/` okrem JAR cache,
- LuckPerms a ostatné databázy,
- `shared/network.env` a Velocity secret.
- `proxy/plugins/skybit-auth/accounts.properties`, bez ktorého by sa stratili registrácie hráčov.

Pred aktualizáciou vytvor offline zálohu. Bootstrap s `-Force` aktualizuje JAR-y, ale produkciu neaktualizuj bez staging smoke testu.

## Databáza

Lokálny režim funguje so SQLite/H2 jednotlivých pluginov. Pred verejným network launchom použi MariaDB/MySQL pre LuckPerms a všetky pluginy, ktoré majú zdieľať dáta medzi servermi. Nikdy necommituj reálne heslo z `shared/network.env`.

## Stop a logy

`Stop-All.ps1` ukončí procesy podľa PID. Pre produkčný hosting preferuj RCON/panel alebo service manager, ktorý odošle konzolový `stop` a počká na čisté uloženie sveta. Logy sú v `servers/<server>/logs/latest.log` a `proxy/logs/latest.log`.
