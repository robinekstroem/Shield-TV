# Tv för Windows

Samma app som på Android, som vanligt Windows-program: Live TV, filmer och
serier från en M3U-länk eller ett Xtream-konto. Installeras genom att
dubbelklicka på **Installera Tv** — ingenting annat behöver installeras, och
GitHub behövs inte. Byggd med Compose Multiplatform, och spelar upp med
VLC:s motor.

Det här är första testversionen (0.1).

## Status

**Finns:**
- Källor: lägga till M3U-länk eller Xtream-konto, uppdatera, ta bort. En
  `get.php`-länk känns igen som Xtream, precis som på Android.
- Live TV: kategorier, sök, kanallista med loggor och vad som sänds nu och
  sedan på varje kanal, kanalbyte i spelaren.
- Tablå: rutnät med kanaler och tider, två timmar bakåt och ett dygn framåt.
  Klick på ett program visar mer; det som sänds nu går att starta direkt.
  Hämtas från leverantören (Xtream: kontots tablå, M3U: url-tvg i listan)
  och uppdateras var tolfte timme. Kanaler kopplas med samma regler som
  Android-appen: hellre ingen tablå än fel tablå.
- Filmer: omslag i rutnät, kategorier, sök. Klick öppnar filmens sida med
  handling, år, genre, speltid, betyg, regi, skådespelare och trailer —
  uppspelningen startar först med **Spela**.
- Serier: samma rutnät. Seriesidan har handling, trailer, säsonger och
  avsnitt, som hämtas när serien öppnas.
- Spelaren: paus, spola, volym, helskärm, TS/HLS-val per Xtream-källa.
  **Ljud och undertext** väljs i spelaren; språket du väljer används sedan
  automatiskt för nästa kanal och film.
- Bild: full upplösning upp till 4K, färgomvandling på grafikkortet med rätt
  färgmatris för HD och SD, skarp bikubisk uppskalning, och avflätning av
  radflätade kanaler i dubbel bildfrekvens. Upplösningen syns bredvid titeln.

**Inte än** (finns på Android): favoriter, fortsätt titta, omslag från TMDB,
sök över hela biblioteket.

## Installera

1. Packa upp hela zip-filen: högerklicka på den och välj **Extrahera alla**.
2. Öppna den uppackade mappen och dubbelklicka på **Installera Tv**.
   Windows varnar första gången eftersom filen kommer från internet —
   klicka **Mer information** och **Kör ändå** (eller **Kör**).
3. Vänta. Fönstret visar hur långt det kommit. Första gången hämtas Java,
   Gradle, VLC och bibliotek, ungefär 1 GB, och det tar 10–20 minuter.
4. Tv startar när det är klart och finns sedan i Start-menyn och på
   skrivbordet.

Tv installeras bara för ditt konto, i `%LOCALAPPDATA%\Programs\Tv`, utan
administratörsrättigheter. Det som behövs för att bygga hamnar i
`%LOCALAPPDATA%\TvBygg` — inte i Windows. Låt det ligga kvar så går nästa
gång fortare, eller ta bort mappen.

**Ny version:** packa upp den nya zip-filen och dubbelklicka på
**Installera Tv** igen. Den nya ersätter den gamla; källor och bibliotek
ligger kvar. Versionen står under Inställningar → Om appen.

**Avinstallera:** Inställningar i Windows → Appar → Tv.

**Om bygget misslyckas** kopierar fönstret felet automatiskt. Klistra in
det i chatten med Ctrl+V.

## Bygga på GitHub (valfritt)

Workflowen **Build Windows** finns kvar men startar bara för hand: Actions →
Build Windows → Run workflow. Den bygger en installationsfil, `Tv.msi`, och
lägger den under Releases som **Tv för Windows**. Releasen blir aldrig
"Latest", så APK:ns releaser påverkas inte.

## Använda

### Tangenter i spelaren

| Tangent | Gör |
|---|---|
| Mellanslag / K | Paus / spela |
| Upp / ner | Byt kanal (live), volym (film och serier) |
| Page Up / Page Down | Byt kanal |
| Vänster / höger | Spola 10 sekunder (film och serier) |
| F / F11 / dubbelklick | Helskärm |
| M | Ljud av / på |
| Esc | Lämna helskärm, annars stäng spelaren |

Helskärm är ett eget fönster över skärmen appen står på; klick på en annan
skärm lämnar det i fred. Esc eller dubbelklick går tillbaka.

Klick i bilden pausar. Muspekaren och kontrollerna försvinner efter några
sekunder och kommer tillbaka när musen rör sig.

## Var data sparas

| Vad | Var |
|---|---|
| Källor och inställningar | `%APPDATA%\se.norrsken.tv` |
| Biblioteket (cache) | `%LOCALAPPDATA%\se.norrsken.tv\library` |

Lösenord, och M3U-adresser som bär inloggningen, krypteras med Windows eget
skydd (DPAPI). De går bara att läsa av ditt Windows-konto på den här datorn.
Biblioteket är krypterat på samma sätt, eftersom kanaladresserna ofta
innehåller inloggningen. Går skyddet inte att använda sparas inloggningen
inte alls — aldrig i klartext.

Vill du börja om: stäng appen och ta bort de två mapparna.

## Kod som delas med Android-appen

Sju filer används av båda plattformarna, ordagrant. Windows-bygget kopierar
in dem från `app/` vid varje bygge:

- `data/playlist/M3uParser.kt`, `SourceDetection.kt`, `ImportState.kt`
- `data/remote/PlaylistDownloader.kt`
- `data/xtream/XtreamClient.kt`, `XtreamModels.kt`
- `data/security/UrlSanitizer.kt`

En rättning i någon av dem gäller alltså båda. Regeln är att de ska förbli
ren Kotlin: en `android.*`-import i någon av dem och Windows-bygget slutar
kompilera. Varje fil har en kommentar högst upp som säger det. Flyttas eller
döps en fil om: uppdatera listan `sharedFromAndroid` i `build.gradle.kts`.

## Struktur

```
windows/
├── build.gradle.kts        Bygget: versioner, delad kod, paketering
├── icon.ico                Samma VHS-kassett som Android-ikonen
├── verktyg/installera.ps1  Det "Installera Tv" (i projektmappen) kör
└── src/main/kotlin/se/norrsken/tv/desktop/
    ├── Main.kt             Fönstret
    ├── AppState.kt         Allt tillstånd: källor, import, uppspelning, tangenter
    ├── data/               Modeller, lagring, DPAPI, import
    ├── player/             VLC och bildvägen in i appen
    ├── platform/           Mörk titelrad, vaken skärm
    └── ui/                 Skärmarna
```

## Tekniska val

**Bilden ritas av appen, inte av VLC.** VLC avkodar och lämnar varje bildruta
i minnet; appen ritar den. Det gör att kontrollerna kan ligga ovanpå bilden —
ett inbäddat VLC-fönster hamnar alltid överst och döljer allt annat.

**Färgerna görs på grafikkortet.** VLC lämnar rutan som YUV-plan (I420) i full
upplösning, och en shader (`player/YuvRenderer.kt`) gör om till RGB: BT.709
för HD och BT.601 för SD, TV-nivåerna 16–235 utsträckta, färgplanen placerade
som i H.264/HEVC. Luminansen skalas bikubiskt (Catmull-Rom). Går shadern inte
att använda faller appen tillbaka på RGB från VLC — det syns under
Inställningar → Bild och ljud.

**VLC 3 och vlcj 4.8.3 är låsta med flit.** vlcj 5 kräver VLC 4, som inte är
släppt. vlcj 4.10 och senare ändrade det API som bildvägen bygger på.

**TS som standard för Xtream-live.** VLC spelar rå TS stabilare än HLS —
tvärtom mot ExoPlayer på Android. Går att byta per källa under Inställningar.

**Installera Tv bygger på den egna datorn.** Skriptet
(`verktyg/installera.ps1`, Windows PowerShell 5.1) hämtar Java, Gradle och
VLC till en egen mapp, kör `createDistributable` och kopierar appen till
`%LOCALAPPDATA%\Programs\Tv` med genvägar och en post under Appar i Windows.
Java-runtimen och VLC ligger i appen, så datorn behöver inget eget.

**GitHub-bygget gör en MSI per användare** via jpackage. Fast
uppgraderings-id och stigande versionsnummer gör att en ny fil ersätter den
gamla i stället för att installeras bredvid.

## Felsökning

**"Uppspelningen behöver VLC".** VLC följde inte med i bygget — syns som en
varning i Actions-körningen. Kör bygget igen; som nödlösning fungerar också
en installerad VLC (64-bit) från videolan.org.

**Kanaler startar inte eller hackar.** Byt Live-format (TS/HLS) för källan
under Inställningar. Säger felet att servern nekade kan max antal samtidiga
anslutningar vara nått — stäng appen på andra enheter.

**Bilden är hoptryckt eller utdragen.** Säg till vilken kanal. Appen läser
pixelformen från strömmen, men alla leverantörer anger den inte.

**Ljud men ingen bild.** Står det "Endast ljud" är det en radiokanal. Annars:
säg till vilken kanal och vilket format.
