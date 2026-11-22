# Glimt

En lätt IPTV-app för Android TV: filmer och serier från en M3U-länk, med automatisk **svensk undertext** från OpenSubtitles och SubDL.

## Bygga

1. Skapa ett nytt repo på GitHub och pusha hela den här mappen till `main`:
   ```
   git init && git add . && git commit -m "Glimt"
   git branch -M main
   git remote add origin git@github.com:<ditt-konto>/glimt.git
   git push -u origin main
   ```
2. Under fliken **Actions** byggs APK:n automatiskt (cirka 5 minuter).
3. Färdig APK hamnar under **Releases** som `glimt.apk`.

## Installera på TV:n

- **Publikt repo:** installera appen *Downloader* på TV:n och skriv in
  `https://github.com/<ditt-konto>/glimt/releases/latest/download/glimt.apk`
  Samma adress ger alltid senaste versionen.
- **Privat repo:** ladda ner APK:n på datorn och kör `adb install -r glimt.apk`.

Varje bygge signeras med samma nyckel (`app/glimt.keystore`), så nya versioner installeras ovanpå gamla utan att inställningarna försvinner.

## Använda

- **Inställningar:** klistra in M3U-länken och dina API-nycklar (gratis):
  - OpenSubtitles: skapa konto → *API consumers* → skapa en app → kopiera nyckeln.
  - SubDL: konto → *API*.
- Undertextspråk är `sv` som standard. Det går att byta till vilken språkkod som helst.
- **I spelaren:** undertext hämtas automatiskt. Tryck **UPP** (eller Meny/gul knapp) för att byta till en annan version, söka igen eller stänga av.

## Bra att veta

- Bara filmer och serier läses in. Live-kanaler hoppas över, så även stora listor går snabbt.
- Serier känns igen på `/series/` i länken eller på `S01E02` i titeln.
- Svenska `.srt`-filer i Windows-1252 konverteras automatiskt, så att åäö blir rätt.
