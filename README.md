# Norrsken — Fas 1, import och omslagshämtning

Android- och Android TV-klient för IPTV, film och serier.

Fas 1 är klar: projektarkitektur, navigation, designsystem och databas.
Ovanpå den ligger två fungerande importvägar — M3U-fil och Xtream-API.
Kanaler hamnar under Live TV, filmer och serier i rutnät med omslag.
Att trycka på något gör ännu ingenting: spelaren är Fas 3.

**Uppdaterar du från förra versionen: avinstallera appen först.** Databasen
har fått nya kolumner och kastas i stället för att migreras (se DataModule —
det måste bytas mot riktiga migrationer innan release).

## Innan du bygger

1. **Öppna projektet i Android Studio en gång.** Gradle wrapper (`gradlew`,
   `gradle/wrapper/gradle-wrapper.jar`) genereras då. Committa den — GitHub
   Actions-bygget använder den.
2. **Byt namn.** `Norrsken` är en arbetstitel. Paketnamn och `applicationId`
   ligger i `app/build.gradle.kts` och i mappstrukturen under
   `app/src/main/java/`. Enklast via Refactor → Rename i Android Studio.
3. **Versionerna i `gradle/libs.versions.toml` är satta mot september 2026.**
   Om någon inte går att hämta säger Gradle vilka som finns. Byt där, inte i
   build-filerna.

## Struktur

```
core/device      Enhetsdetektering — TV eller handhållen, D-pad eller touch
core/ui/theme    Designsystemet: palett, mått, rörelse, typografi
core/ui/component Byggstenar. FocusableSurface är basen för allt klickbart
data/db          Room: entiteter, DAO:er, konverterare
data/repository  Gränssnittet skärmarna ser. Aldrig Room direkt
di               Hilt-moduler
navigation       Destinationer
ui/shell         Ramen: TV-meny, mobilmeny, NavHost
feature/*        En mapp per huvudområde
```

Ett Gradle-modul, strikt paketseparation. När ett område blir tungt nog att
förtjäna ett eget modul går det att bryta ut utan att flytta kod, eftersom
beroendena redan pekar åt rätt håll: `feature` → `data/repository` → `data/db`.

## Designbeslut som är avsiktliga

**Gränssnittet är färglöst.** Bakgrund, ytor och text ligger på kall gråskala.
Den enda mättade färgen på skärmen ska komma från innehållet — omslagets
extraherade färg i ambient glow. Färgstarkt chrome konkurrerar med postern.

**Fokus är ljus, inte kulör.** På tre meters avstånd läser ögat ljusstyrka
snabbare än färg, och en ljus ring fungerar över vilken backdrop som helst.
All markering går genom `FocusableSurface`, så fokus aldrig kan försvinna.

**TV-menyn är ihopdragen.** Horisontellt utrymme är det dyraste på en TV. En
permanent utfälld meny äter 200 dp av varje posterrad, hela tiden, för
information som bara behövs i navigeringsögonblicket.

**Mobilen har fem flikar, inte åtta.** Favoriter, Sök och Inställningar nås
inifrån skärmarna. Åtta ikoner i en bottenrad blir oträffbara.

**EPG-tider lagras i UTC.** Offset läggs på vid visning. Annars måste hela
tabellen skrivas om varje gång användaren ändrar offset.

**Manuella EPG-kopplingar skrivs aldrig över.** `epg_mappings` är en egen
tabell med `source`, och synken rör bara rader som inte är `MANUAL`.

## Telefon först

Det finns inget att plocka bort för att köra enbart på telefon. `DeviceProfile`
frågar systemet om enheten är en TV; på en telefon komponeras TV-menyn aldrig
och TV-måtten laddas aldrig. Koden ligger där men kör inte.

Att riva ut den nu hade bara betytt att bygga den igen senare.

## Bygga och installera

### 1. Wrappern är inte längre ett krav

Workflow:en installerar Gradle själv (`gradle-version: current`) och
använder wrappern bara om den finns. Bygget går alltså igenom även på ett
repo utan `gradlew`.

Committa den ändå när du öppnat projektet i Android Studio första gången —
`gradlew`, `gradlew.bat` och `gradle/wrapper/gradle-wrapper.jar`. Då bygger
CI med exakt samma Gradle-version som du kör lokalt, vilket tar bort en hel
klass av "det funkar hos mig".

**Symptomen om något ändå går fel här:** exit code 127 betyder att
`./gradlew` inte finns. Exit code 126 betyder att den finns men saknar
körbiten — vanligt när filen committats från Windows. Workflow:en kör
`chmod +x` för att täcka det andra fallet.

### 2. Hämta APK:n

- **Vanlig push till main** → APK:n ligger under Actions → körningen →
  Artifacts. Nedladdningen kräver inloggning och kommer som en zip.
- **Tagg** (`git tag v0.1.0 && git push --tags`) → APK:n läggs som en
  release med en öppen direktlänk. Använd den för Shield.

### 3. Installera

**Telefon:** ladda ned, packa upp zippen, tryck på APK-filen, godkänn
installation från okänd källa.

**Shield:** enklast är adb från datorn:

    adb connect <shield-ip>:5555
    adb install -r norrsken-debug.apk

Nätverksfelsökning slås på under Inställningar → Enhetsinställningar →
Utvecklaralternativ. Utan dator: släpp en release enligt punkt 2 och hämta
länken med appen Downloader. En Actions-artifact fungerar inte den vägen —
den är både inloggningsskyddad och zippad.

Tillåt först okända källor: Inställningar → Enhetsinställningar →
Säkerhet och begränsningar → Okända källor.

### Om release-bygget

`assembleDebug` signeras med Androids debug-nyckel och installeras utan
vidare. `assembleRelease` gör det inte — den behöver en egen keystore, och
APK:n går inte att installera osignerad. Release-bygget kör dessutom R8 med
`isMinifyEnabled = true`, vilket brukar kräva några omgångar keep-regler för
Hilt och Room första gången. Kör debug tills appen faktiskt gör något.

Debug-bygget har `applicationIdSuffix = ".debug"`, så det krockar inte med
en framtida release-version — men båda visar samma namn och ikon.

## Första körningen — vad du ska titta efter

Appen är byggd för både telefon och Android TV men har aldrig körts på en
enhet. Kolla det här i tur och ordning, för det är där det brister först.

**Nvidia Shield, bara med fjärrkontrollen:**

1. Något har fokus direkt när appen startar — menyn till vänster.
2. Höger flyttar in i innehållet, vänster tillbaka till menyn.
3. Menyn fälls ut med etiketter när fokus är i den, dras ihop när du lämnar.
4. Upp/ner i kanallistan tappar aldrig fokus. Fastnar den eller försvinner
   markeringen är det fokushanteringen i `FocusableSurface` som brister.
5. Back stänger appen från Start, inte mitt i en lista.

**Telefon:**

1. Rubriker hamnar under statusfältet eller hålkameran? Då täcker inte
   `statusBarsPadding` i `AppShell` allt.
2. Vrid skärmen under scroll — inget får byggas om.
3. Bottenmenyn ska ligga ovanför gestfältet, inte under.

**Båda:**

- Importera en riktig spellista. Kanalerna ska dyka upp under Live TV.
- Scrolla i Filmer med en TMDB-nyckel insatt. Omslag ska fyllas i medan du
  scrollar, inte alla på en gång.

## Att skriva in länken på en TV

En M3U-URL med inloggningsuppgifter är ofta 80–120 tecken. Att knacka in den
med en fjärrkontroll är plågsamt. Tre vägar som fungerar:

- Google TV/Android TV-fjärrkontrollen i Google Home-appen på telefonen ger
  ett riktigt tangentbord mot TV:n.
- `adb connect <tv-ip>` och sedan `adb shell input text "<url>"`. Tänk på att
  `&` och `?` måste escapas.
- Testa först på telefonen, där du kan klistra in. Databasen är lokal per
  enhet, så importen måste göras om på TV:n — men du vet åtminstone att
  länken fungerar innan du börjar knappa.

## Omslag som saknas

Många leverantörer skickar filmer och serier helt utan omslagsbild. De
fylls i från TMDB — men **inte vid import**.

Med 3000 filmer i biblioteket skulle en import-tidig hämtning betyda 3000
anrop innan användaren ser första bilden. Importen går från en halv minut
till tio, och tjänsten stryper kontot på vägen. I stället hämtas omslaget
när kortet kommer in på skärmen: du scrollar, bilden dyker upp, den sparas
i databasen och finns där nästa gång. Fyra samtidiga anrop, aldrig fler.

### Nyckeln är din egen

Ingen API-nyckel följer med appen. En nyckel i en APK är inte hemlig — vem
som helst kan packa upp filen och läsa den — och kvoten och avstängningen
hade landat på den som lade in den. Registrera en gratisnyckel hos
themoviedb.org och klistra in den under Inställningar. TMDB kräver också att
källan anges; attributionen står i samma vy.

### Matchningen är avsiktligt sträng

Regeln från kravspecen sitter i `TitleMatcher.kt`: hellre ingen metadata än
fel metadata. En felaktig affisch är värre än en tom ruta, för användaren
tror på den.

Därför krävs att titeln stämmer **exakt** efter normalisering — ingen fuzzy
matchning på "liknar tillräckligt". Finns årtalet krävs att det stämmer inom
ett år (releasedatum skiljer sig mellan regioner). Saknas årtal och flera
filmer heter likadant appliceras ingenting alls.

Titlar där sökningen gav svar men ingen säker träff markeras `AMBIGUOUS` och
frågas inte om igen. Nätfel och saknad nyckel lämnar raden `UNMATCHED`, så
den försöks på nytt när det går.

## M3U eller Xtream

De flesta leverantörer ger dig en länk som ser ut så här:

    http://host:8080/get.php?username=abc&password=123&type=m3u_plus

Det är ett Xtream-konto i förklädnad. Klistrar du in den upptäcker appen det
och byter själv över till API:et, för den platta M3U-filen ger bara kanaler —
API:et ger kategorier, filmer med omslag och betyg, serier med säsonger, och
en EPG-adress. Du får veta att bytet skedde, du behöver inte välja.

Har du uppgifterna separat: växla till Xtream-fliken och fyll i server,
användarnamn och lösenord.

### Om Xtream-klienten

Svaren parsas med `org.json`, inte kotlinx.serialization. Det är medvetet.
Panelerna är slarviga med typer — `stream_id` kommer som tal hos en
leverantör och sträng hos nästa, `rating` kan vara `""`, `null`, `"7.4"`
eller `7.4` i samma svar. En strikt parser kraschar på det. Det kostar
mer kod men ger en app som fungerar hos fler leverantörer.

Serieavsnitt hämtas **inte** vid import. Ett `get_series_info`-anrop per
serie betyder tusentals anrop vid 3000 serier — det tar halvtimmar och vissa
paneler stryper kontot långt innan dess. Avsnitten hämtas när användaren
öppnar serien.

Live-strömmar byggs som `.m3u8` som standard. Enstaka paneler serverar bara
`.ts`; fältet `liveExtension` på spellistan finns för det.

## Vad M3U-importen faktiskt gör

1. Hämtar filen. Följer omdirigeringar även mellan http och https, skickar en
   spelarlik User-Agent (många servrar svarar annars 403), och packar upp
   gzip även när servern glömt sätta Content-Encoding.
2. Parsar rad för rad ur strömmen, inte via en sträng i minnet. Läser
   `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-chno`, catch-up och
   `#EXTGRP`. Plockar också upp EPG-adressen ur `#EXTM3U`-raden om den finns.
3. Skapar kategorier av grupperna, rensar det gamla och skriver kanalerna i
   satser om 500 med verklig progress.

Loggor laddas med Coil. Saknas loggan visas kanalnumret — aldrig en annan
kanals bild.

## Nästa fas

Fas 2 fortsätter: Xtream-API, flera källor samtidigt, VOD och serier.
Fas 3: Media3-spelaren och kanalbyte.

Innan du går vidare — bygg det här, importera din riktiga spellista och se
att kanalerna dyker upp på både telefon och TV.
