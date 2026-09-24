# Norrsken 3 – genomförda fixar

- Favoriter är nu en riktig funktion i stället för en placeholder.
- Film kan läggas till/tas bort som favorit från filmdetaljen.
- Serie kan läggas till/tas bort som favorit från seriedetaljen.
- Livekanal kan läggas till/tas bort som favorit från spelarens kontrollpanel.
- Favoritsidan visar separata rails för filmer, serier och kanaler och navigerar till rätt innehåll.
- `MediaKind` har utökats med `SERIES`; enum lagras som text och kräver därför ingen schemamigration.
- Befintlig tittarhistorik/Continue Watching/resume har verifierats som inkopplad i spelaren: periodisk sparning, sparning vid release och seek till sparad position finns.
- CredentialStore degraderar inte längre tyst till klartext om Android Keystore misslyckas.
- ImportCoordinator fångar fel från säker lagring och visar importfel i stället för att bakgrundsjobbet bara dör.
- README har uppdaterats så projektstatusen motsvarar koden.

## Ej ändrat medvetet

`cleartextTrafficPermitted=true` är kvar eftersom IPTV-källor kan ligga på godtyckliga HTTP-värdar. En statisk allowlist per domän skulle göra användarens egna HTTP-källor obrukbara. Det bör i stället ses som en kompatibilitetsinställning för denna typ av klient.

Stalker Portal och större paging-ombyggnad är inte inlagda i denna fix eftersom de är nya funktioner/arkitekturarbete, inte buggfixar av befintliga flöden.

## Cinematic pass 2
- Nytt `CinematicLightField`: två mycket subtila, tema-färgade ljusfält ovanpå artwork för mer filmiskt djup utan tung blur.
- Hero är större och har en diskret `NORRSKEN • FEATURED/SERIES/LIVE`-badge.
- Filmdetaljer har fått `NORRSKEN • CINEMA`, glasliknande metadata-chips och samma dynamiska ljusfält.
- Effekterna återanvänder befintlig temapalett, så Titan/Snö/Midnatt fortsätter kännas som egna teman.
- Ingen ändring av datalager, spelare eller navigation i denna pass; UI-polish hålls isolerad.

## Cinematic pass 2
- Series detail rebuilt to match the full-screen cinematic movie presentation.
- Added animated series hero, NORRSKEN SERIES badge, metadata chips and first-episode primary CTA.
- Episode list upgraded with 16:9 thumbnails, richer episode metadata and TV-focus feedback.
- Series backdrop now blends into the app atmosphere with layered horizontal/vertical scrims and cinematic light field.
- Fixed duplicate `maxLines` in the previous episode-row implementation.
