# SkyBit Management

Lokálna Windows aplikácia na správu celej SkyBit siete.

## Funkcie

- Lobby, Survival, SkyBlock a Velocity na jednej obrazovke
- stav procesu, PID, RAM, CPU a uptime
- online hráči cez Minecraft status ping
- bezpečný štart, stop a reštart
- živá konzola a posielanie príkazov na spravované servery
- prepínanie medzi konzolou, Paper `latest.log` a chybovým logom s filtrom riadkov
- centrum upozornení na pády serverov a dlhšie vysoké využitie CPU
- lokálne ZIP zálohy konfigurácií vybraného servera bez kopírovania živých svetov a databáz
- diagnostický report so stavom služieb a redigovanými IP adresami
- automatická rotácia veľkých konzolových logov
- žiadny verejný management port; ovládanie funguje iba lokálne

## Prvý štart

Dvojklik na `SkyBit Management.exe`.

Servery, ktoré už bežia zo starého `Start-All.ps1`, aplikácia zobrazí ako `ONLINE • EXTERNAL` a kvôli ochrane svetov ich násilne nevypne. Po najbližšom bezpečnom vypnutí ich spusti cez aplikáciu. Odvtedy budú označené `ONLINE • MANAGED` a sprístupní sa konzola, STOP aj REŠTART.

Zálohy sa ukladajú do `backups/manager`, diagnostické reporty do `management/reports` a história upozornení do `management/events.log`.

## Build

Spusti `Build.ps1`. Skript po zostavení automaticky vykoná self-test. Parameter `-Preview` navyše vytvorí snímku rozhrania `preview.png`. Aplikácia používa vstavaný Windows .NET Framework a nepridáva žiadny cudzí runtime ani sieťovú službu.
