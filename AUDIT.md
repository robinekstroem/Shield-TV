# Norrsken — Checkpoint 1: Code audit

Status per denna checkpoint. Skriven ur koden, inte ur minnet.

## Fungerar och är testat på enhet

- M3U-import: hämtning, gzip, omdirigering http↔https, parsning i ström
- Xtream-import: kanaler, filmer, serier, kategorier
- Live TV med kategorier, uppspelning via Media3
- Inställningar med elva verkligt kopplade val
- Tema, navigation, TV- och mobillayout

## Senast tillagt, overifierat på enhet

- Tre teman: Norrsken, Titan, Midnatt. Tema styr palett, fokusglöd,
  rubrikstil och typografins tyngd — inte bara accentfärgen.
- Dolda kategorier: enskilt eller med ordfilter. Innehållet döljs även under
  "Alla" och på hemskärmen. Överlever uppdatering av spellistan.
- Spelaren: helskärm, liggande på telefon, ljud- och undertextval,
  bildformat, undertextstil.
- Titelrensning av hakparentestaggar, med ny matchning mot TMDB.

## Fungerar men är overifierat på enhet

- Film- och serieuppspelning
- Serieavsnitt (lat hämtning per serie)
- TMDB-omslag för titlar utan poster
- Hemskärm med hero och rader

## Halvimplementerat

| Område | Vad som saknas |
|---|---|
| Favoriter | Tabell och DAO finns, ingen skärm och inget som skriver |
| Historik | Tabell och DAO finns, inget skriver — därför ingen "Fortsätt titta" |
| Profiler | Tabell finns, ingen profil skapas, `profileId` är oanvänd |
| Catch-up | `catchupDays` och `catchupSource` importeras men används inte |
| Sök | Skärm finns, DAO-frågor finns, ingen koppling |

## Placeholders

`GuideScreen`, `FavoritesScreen`, `SearchScreen` — tomtillstånd utan funktion.

## Saknas helt

EPG-motorn. Ingen XMLTV-hämtning, ingen parser, ingen matchning, ingen tablå.
Detta blockerar: TV-guide, catch-up, "Nu på TV", påminnelser, programinfo
och fjorton av inställningspunkterna.

Stalker Portal. Tredje protokoll, inte påbörjat.

## Inställningar utan consumer

Inga. Alla nitton fälten i `AppPreferences` läses av riktig kod.

## Säkerhet

| Fynd | Status |
|---|---|
| Loggning av credentials | Ingen risk — projektet har noll loggrader |
| Lösenord i klartext i databasen | **Åtgärdat** — Keystore, AES/GCM |
| URL med uppgifter i sökvägen visades | **Åtgärdat** — `sanitizeUrl` |
| Destruktiv databasmigration | **Åtgärdat** — borttagen, schemat fryst |

## Prestanda

| Fynd | Status |
|---|---|
| Kanallistan kapas vid 500 | Känt, besked visas. Paging återstår |
| `SELECT *` utan paging för film och serier | Återstår — tak 300 |
| Inga index på `matchState` eller `normalizedName` | Återstår, mät först |
| Bild-cachestorlekar ej satta för Coil | Återstår |
| Ingen blockerande I/O på main thread | Kontrollerat — allt går via `@IoDispatcher` |

## Tester

Fyra sviter, 23 fall: M3U-parser, källdetektering, URL-maskering,
metadatamatchning. Skrivna mot ren Kotlin utan Android-beroenden, så de kör
med `./gradlew test` utan emulator.

Testet av metadatamatchningen hittade direkt ett riktigt fel: apostrofer
normaliserades till mellanslag, så "Marvel's Daredevil" och "Marvels
Daredevil" matchade inte varandra.

## Kända brister i utseendet

- **Typsnittet.** Systemets Roboto. Ett riktigt display-typsnitt är den
  enskilt största förbättringen kvar för Titan-känslan.
- **Ikonerna i menyn.** Filmer är en stjärna och Serier en lista, eftersom
  Materials kärnuppsättning inte har bättre. Stjärna betyder favorit för de
  flesta.
- **Fortsätt titta.** Tabellen finns, inget skriver till den. Film som
  öppnas igen börjar från början.
- **Ljudfördröjning.** Media3 har inget enkelt API för det. Kräver egen
  AudioSink.

## Nästa checkpoint

EPG-motorn. Den låser upp fler punkter i masterprompten än något annat.
