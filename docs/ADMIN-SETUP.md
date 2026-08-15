# In-game dokončenie spawnu

Skript vytvorí a overí softvérovú vrstvu. Build spawnu, presné súradnice NPC, WorldGuard regióny a fyzické crate bloky sú zámerne admin úkony — bez dodanej mapy ich nemožno bezpečne umiestniť automaticky.

## 1. SkyBit Survival spawn

1. Postav alebo vlož spawn cez WorldEdit.
2. Nastav Essentials spawn a vytvor WorldGuard región `spawn`.
3. Zakáž v regióne PvP, block break/place, mob damage, explosions a item pickup podľa dizajnu.
4. Vytvor jasné zóny: Tutorial, Shop, Jobs, Quests, Daily, Crates, Auction, Player Market, RTP, Vote a Leaderboards.
5. Ku každej zóne pridaj FancyNpcs NPC a DecentHolograms text. Klik NPC nech vykoná iba hráčsky príkaz, nie privilegovaný admin príkaz.

Odporúčaný tutorial tok:

```text
Vitaj → /rtp → prvý claim → /jobs → /quests → /shop → /ah → /daily
```

## 2. Shop a ekonomika

V EconomyShop admin GUI vytvor kategórie Farming, Blocks, Mining, Wood, Food, Mob Drops, Utility a Decoration. Použi ceny z `docs/SHOP-PRICES.csv`.

Zásady:

- sell je spravidla 20–35 % buy,
- diamanty, emeraldy a Netherite nemajú mať pohodlný serverový sell,
- hopper, spawner a redstone farm výstupy majú denný limit alebo nízky sell,
- auction listing fee 2 % a sale tax 3 % sú money sink,
- EconomyShop chest-shop creation fee je 250 $, default 3 shopy; VIP limity nastav až po ekonomickom teste.

Po každej zmene cien testuj: bone meal slučky, villager trading, raid farmy, iron farmy, gold farmy, fishing a shop buy→craft→sell kombinácie.

## 3. Crates

V Phoenix Crates Lite vytvor Vote, Rare, Epic a Legendary crate. Percentá zobrazuj transparentne. Odmeny majú byť hlavne money, XP, SkyCoins, kozmetika, tagy a rozumné vanilla nástroje. Nedávaj permanentne exkluzívne combat enchanty ani predmety, ktoré obídu ekonomiku.

`SkyBitCore` daily odmeny už fungujú bez crate závislosti. Keď vytvoríš finálne key IDs, pridaj key príkazy do `plugins/SkyBitCore/config.yml` pod príslušný `daily-streak.<day>.commands` alebo quest `reward.commands`.

## 4. Leaderboardy

DecentHolograms/ajLeaderboards môžu použiť:

```text
%skybit_top_level_1_name% / %skybit_top_level_1_value%
%skybit_top_playtime_1_name% / %skybit_top_playtime_1_value%
%skybit_top_kills_1_name% / %skybit_top_kills_1_value%
%skybit_top_miners_1_name% / %skybit_top_miners_1_value%
%skybit_top_fishers_1_name% / %skybit_top_fishers_1_value%
```

Money leaderboard použi cez Vault/Essentials expansion. Vote leaderboard pridaj až po nastavení hlasovacích stránok a Votifier tokenov; bez reálnych endpointov sa nemá predstierať funkčná vote integrácia.

## 5. SkyBlock

BSkyBlock je nainštalovaný ako BentoBox addon. Po prvom štarte skontroluj blueprint, ostrovnú vzdialenosť, reset limity a Challenges rewards. Ekonomiku SkyBlocku drž oddelene od Survivalu, kým neotestuješ prenos itemov a money exploity.

## 6. Ranky

Použi príkazy z `docs/permissions-skybit.txt` na každom backende alebo ich vlož do LuckPerms web editoru. Prefixy a cosmetics sú benefit; rank nesmie predávať combat silu, money multiplikátor ani exkluzívny obsah blokujúci default hráča.
