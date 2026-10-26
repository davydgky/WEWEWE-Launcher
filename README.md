# WEWEWE – ein Launcher mit Spotlight-Suche für Android

Ein Homescreen für Android 16 und neuer, dessen Kern eine geräteweite Suche nach dem Vorbild von iOS
Spotlight ist. Ein Wischen nach unten aus der oberen Bildschirmhälfte öffnet die Suche, und die Tastatur
fährt dabei mit dem Finger hoch. Ohne Eingabe zeigt die Suche Vorschläge, mit Eingabe Treffer aus allen
Quellen, gruppiert und mit dem besten Treffer ganz oben. Alles bleibt auf dem Gerät.

Welche Quelle Android unter welcher Bedingung erlaubt und warum die Architektur so aussieht, steht in
[docs/QUELLEN.md](docs/QUELLEN.md).

## Funktionen

- **Wischgeste mit mitlaufender Tastatur**: Die Tastatur folgt dem Finger Bild für Bild
  (`WindowInsetsController.controlWindowInsetsAnimation`). Loslassen öffnet oder schließt je nach Weg und
  Tempo. Das Suchfeld hat auf dem Homescreen schon den Fokus, ohne Tastatur. Deshalb steht die Verbindung
  zur Tastatur-App, bevor der Finger das Display berührt.
- **Tippen auf einer Hardware-Tastatur** öffnet die Suche direkt vom Homescreen aus.
- **Vorschläge ohne Eingabe**: acht Apps nach Tageszeit, Wochentag und Häufigkeit, dazu passende
  App-Verknüpfungen (die selbst oft genutzten und die wichtigsten der vorgeschlagenen Apps).
- **Treffer mit Eingabe**: „Top-Treffer“, darunter Gruppen je Quelle, sortiert nach ihrem besten Treffer.
  Jedes Wort ist ein Präfix („goo ma“ findet Google Maps). Umlaute gehen in beiden Schreibweisen
  („Müller“, „Mueller“, „Muller“), Telefonnummern ab jeder Stelle, Initialen („gm“). Was man öfter
  auswählt, steigt im Ranking.
- **Quellen**: Apps (auch Arbeitsprofil), App-Verknüpfungen, Kontakte (mit Anrufen/Nachricht direkt im
  Treffer), Kalender, Velo-Verlauf und -Favoriten, Notizen aus freigegebenen Ordnern (Obsidian öffnet
  direkt die Notiz), Dokumente (Name, bei Text/Markdown/PDF auch Inhalt) und Fotos nach dem Text darin.
- **Texterkennung nur beim Laden**: ML Kit mit gebündeltem Modell erkennt Text in Fotos auf dem Gerät.
  Das passiert in einem `JobScheduler`-Job, der nur bei angeschlossenem Ladegerät läuft und beim Abstecken
  pausiert.
- **Index aktualisiert sich selbst**: `LauncherApps.Callback`, `ContentObserver` für Kontakte, Kalender,
  Medien und Velo, MediaStore-Generationen für inkrementelle Bild- und Dokumentänderungen, regelmäßiger
  Abgleich der Notiz-Ordner.
- **Homescreen**: Seiten mit allen Apps (alphabetisch), Dock (Standard-Apps oder selbst gewählt),
  Suchen-Knopf, langes Drücken auf eine App für Verknüpfungen, Dock, App-Info und Deinstallieren; langes
  Drücken auf den Hintergrund für Hintergrundbild und Einstellungen. Statusleiste und Beschriftungen
  passen sich an helle und dunkle Hintergrundbilder an.
- **Einstellungen** („Launcher & Suche“): jede Quelle einzeln abschaltbar, fehlende Berechtigungen mit
  einem Tipp erteilen, Notiz-Ordner verwalten, Fortschritt der Texterkennung, Messwerte, Index neu aufbauen.
- Deutsch und Englisch, Hell- und Dunkelmodus, Material-You-Farben.

## Zeitbudget und Messung

| Messwert | Budget | Gemessen von … bis … |
|---|---|---|
| Tastatur | < 150 ms | Wischgeste erkannt (Touch-Slop überschritten) → erster Frame, in dem die Tastatur sichtbar ist |
| Ergebnisse | < 50 ms | Eingabe geändert → Frame, der die Ergebnisse zeigt (konservativ: bis zum Beginn des Folgeframes) |

Beides misst die App selbst auf dem Gerät. Unter *Launcher & Suche › Messwerte* stehen Median,
95. Perzentil und Maximum der letzten 200 Messungen. Jeder Einzelwert steht außerdem im Logcat:

```bash
adb logcat -s LauncherPerf
```

Der Suchkern allein braucht für 26.000 Einträge (darunter 20.000 Fotos mit Text) im Schnitt 3–4 ms je
Tastendruck auf einem Desktop-JVM. Ein Unit-Test (`SearchPerformanceTest`) wacht darüber. Die Suche läuft auf
einem eigenen Thread, und eine neue Eingabe bricht die vorige Suche ab.

## Datenschutz

- Die App hat **keine Internetberechtigung**. ML Kit bringt sie mit, sie wird im Manifest entfernt, und die CI
  prüft das am fertigen APK (`aapt2 dump permissions`).
- Kein Backup (`allowBackup=false`). Der Index liegt im App-Speicher: OCR-Text, Dokument- und
  Notizinhalte in SQLite, alles andere nur im Arbeitsspeicher.
- Eine Websuche (letzte Zeile der Treffer) übergibt die Eingabe erst auf Antippen an Velo.

## Technik

| | |
|---|---|
| Sprache / UI | Kotlin 2.4, Jetpack Compose (BOM 2026.09), Material 3 |
| Build | AGP 9.3, Gradle 9.7, Version Catalog |
| SDK | `compileSdk`/`targetSdk` 37 (Android 17), `minSdk` 36 (Android 16) |
| Texterkennung | ML Kit Text Recognition v2, gebündeltes Lateinisch-Modell |
| Persistenz | DataStore Preferences, SQLite |

### Aufbau

```
core/                          Suchkern ohne Android (Kotlin/JVM, Unit-Tests)
├── TextNormalizer.kt          Kleinschreibung, Diakritika, Umlaut-Varianten, Initialen
├── SearchIndex.kt             Präfixindex: sortierte Tokens, Postings, Feldgewichte
├── SearchEngine.kt            Teilindizes je Quelle, Top-K je Gruppe, Top-Treffer
├── Predictor.kt               Vorschläge nach Tageszeit/Wochentag/Häufigkeit; Engagement
└── Snippets.kt                Textausschnitt um den Treffer
app/src/main/java/io/github/davydgky/launcher/
├── MainActivity.kt            Homescreen-Activity, Hintergrundbild-Farben
├── apps/                      Apps und Verknüpfungen (LauncherApps), Standard-Apps
├── sources/                   Eine Klasse je Suchquelle
├── index/                     SQLite-Speicher, MediaStore-Abfragen, Lade-Job (OCR, PDF-Text)
├── search/                    Repository, ViewModel, Vorschläge, Icons, Öffnen von Treffern
├── usage/                     Nutzungsstatistik (UsageStatsManager)
├── ui/                        Geste, Tastatursteuerung, Suchpanel, Trefferzeilen
├── home/                      Homescreen mit Seiten und Dock
└── settings/                  Einstellungen
```

## Velo anbinden

Velo stellt Verlauf und Favoriten über einen `ContentProvider` bereit:

| | |
|---|---|
| Authority | `io.github.davydgky.browser.searchdata` |
| Berechtigung | `io.github.davydgky.browser.permission.READ_SEARCH_DATA` (Laufzeitberechtigung) |
| `content://…/history` | `url`, `title`, `last_visited`, `visits` |
| `content://…/bookmarks` | `title`, `url`, `position` |

Die Berechtigung erteilt der Nutzer in *Launcher & Suche › Velo-Verlauf und -Favoriten*. Änderungen meldet
Velo mit `notifyChange`, und der Launcher liest dann neu ein.

## Bauen

```bash
./gradlew assembleDebug                 # APK unter app/build/outputs/apk/debug/
./gradlew :core:test testDebugUnitTest  # Unit-Tests
```

Jeder Push baut über GitHub Actions: Build, Unit-Tests, Lint und die Prüfung auf fehlende
Internetberechtigung. Danach bedient ein Emulator-Test (`scripts/emulator_smoke.py`) den Launcher: Er macht
ihn zum Standard, wischt nach unten und prüft, dass die Tastatur erscheint. Er tippt einen App-Namen, legt
ein Foto mit Text ab, simuliert das Laden, lässt die Texterkennung laufen und sucht nach einem Wort aus dem
Foto. Die signierte APK liegt als Artefakt `launcher-apk` am Workflow-Lauf. Sie ist mit dem Schlüssel aus
`keystore/` signiert, damit jede Version die vorige ersetzen kann.

## Bekannte Grenzen

- Notizen nur aus Apps, die Dateien in einem Ordner ablegen. Google Keep, Samsung Notes und Cloud-Notizdienste
  bieten keine lokale Schnittstelle (siehe [docs/QUELLEN.md](docs/QUELLEN.md)).
- Notiz-Ordner melden Änderungen nicht zuverlässig. Sie werden beim Zurückkehren zum Homescreen abgeglichen,
  höchstens alle fünf Minuten.
- Der Private Space bleibt unsichtbar (dafür bräuchte der Launcher `ACCESS_HIDDEN_PROFILES`).
- Texterkennung nur für lateinische Schrift. Office-Dateien werden nur über den Namen gefunden.
- Für eine Veröffentlichung bei Google Play wären Erklärungen für `READ_CONTACTS` (ab Android 17),
  `MANAGE_EXTERNAL_STORAGE` und `PACKAGE_USAGE_STATS` nötig.

## Drittanbieter

- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2), ML Kit Terms of Service
