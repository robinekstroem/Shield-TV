# Tv för Mac

Samma app som i Windows: hem, live-tv, tablå, filmer, serier, genrer,
historik, Min lista, TMDB och alla teman. Den byggs från samma kod
(`windows/`), med VLC:s motor som spelare, precis som i Windows.

GitHub bygger den på riktiga Macar, en gång för Apple Silicon (M1 och
senare) och en gång för Intel. Bygget heter **Build Mac** och lägger
filerna under Releases som **Tv för Mac**.

## Installera eller uppdatera

Öppna **Terminal** på Macen (Program → Verktygsprogram → Terminal), klistra
in kommandot som står under Releases → Tv för Mac och tryck Retur. Det ser
ut så här, med ditt repo i adressen:

    curl -fsSL https://github.com/<ägare>/<repo>/releases/download/mac-senaste/installera-tv.sh | bash

Skriptet väljer rätt version för datorn, stänger Tv om den är igång, lägger
den nya i Program och startar den. Samma kommando uppdaterar senare. Källor,
historik och inställningar ligger kvar, i
`~/Library/Application Support/se.norrsken.tv`.

## För hand

Ladda ner rätt .dmg under Releases (Apple Silicon eller Intel; Apple-menyn →
Om den här datorn visar vilken), öppna den och dra **Tv** till **Program**.

Appen är inte registrerad hos Apple, så första gången säger macOS att den
inte kan kontrolleras. Öppna då **Systeminställningar → Integritet och
säkerhet**, scrolla ner och tryck **Öppna ändå**. Det behövs igen efter
varje uppdatering för hand, men inte med kommandot ovan.

## Filer

- `installera-tv.sh`: installationsskriptet. Publiceras av bygget, med
  repots namn ifyllt.
- Ikonen ligger i `windows/icon.icns`, bredvid Windows-ikonen.
- Bygget: `.github/workflows/mac.yml`.
