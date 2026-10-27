# Suchquellen: was Android erlaubt

Stand: Android 16 (API 36, `minSdk`) und Android 17 (API 37, `targetSdk`). Grundlage sind die
Plattform-Referenz und die Verhaltensänderungen von Android 17. Die Android-17-Änderungen betreffen
Kontakte (Konto-Spalten in `ContactsContract.Data` entfallen, Play-Richtlinie für `READ_CONTACTS`),
aber weder Kalender, Medien, `LauncherApps` noch `UsageStatsManager`.

Die wichtigste Voraussetzung für fast alles: **Die App muss der Standard-Launcher sein** (Rolle
`android.app.role.HOME`). Ohne diese Rolle gibt es keine App-Verknüpfungen, und die Suche über die
Wischgeste gibt es ohnehin nur auf dem eigenen Homescreen.

## Übersicht

| Quelle | Erlaubt? | Bedingung | Was nicht geht |
|---|---|---|---|
| Apps | Ja | `<queries>` für `MAIN/LAUNCHER` (Paket-Sichtbarkeit), `LauncherApps` | Private Space nur mit `ACCESS_HIDDEN_PROFILES` **und** Home-Rolle; die Einstellungsseite des Private Space ist für Dritt-Launcher nicht aufrufbar (`@hide`) |
| App-Verknüpfungen | Ja | Rolle **HOME** (`LauncherApps.hasShortcutHostPermission()`) | Verknüpfungen, die *andere* Launcher angeheftet haben (`FLAG_MATCH_PINNED_BY_ANY_LAUNCHER` nur für den Assistenten) |
| Zuletzt / häufig genutzte Apps | Ja, zweistufig | Eigene Starts: ohne Berechtigung. Alle Starts (auch aus Benachrichtigungen, Übersicht): `PACKAGE_USAGE_STATS`, vom Nutzer in *Einstellungen › Nutzungsdatenzugriff* zu erteilen (kein Laufzeitdialog) | Androids eigene Vorhersage (`AppPredictionManager`) ist System-API, für Dritt-Apps gesperrt |
| Kalender | Ja | Laufzeitberechtigung `READ_CALENDAR` | Nur Kalender, die per Konto-Sync oder lokal im Kalender-Provider liegen; reine Web-Kalender ohne Sync-Adapter fehlen |
| Kontakte | Ja | Laufzeitberechtigung `READ_CONTACTS` | Ab Android 17 nur noch per Play-Erklärung zulässig, wenn der neue Kontakt-Picker nicht reicht. Für einen Suchindex reicht er nicht (Zugriff nur auf einzeln gewählte Kontakte, sitzungsgebunden). Für Installationen außerhalb von Play ändert sich nichts |
| Notizen | **Kein Systemweg** | Siehe unten: nur Apps, die Dateien ablegen (Ordnerfreigabe per SAF) | Google Keep, Samsung Notes, OneNote, Evernote, Notion: keine lokale Schnittstelle |
| Dokumente | Ja, mit Sonderzugriff | `MANAGE_EXTERNAL_STORAGE` („Zugriff auf alle Dateien“, vom Nutzer in den Einstellungen) **oder** einzelne Ordner per Storage Access Framework | Ohne Sonderzugriff sieht `MediaStore` fremde PDFs/Office-Dateien nicht (Scoped Storage). SAF verweigert die Wurzel des Speichers, den `Download`-Ordner selbst und `Android/data` |
| Bilder mit erkanntem Text | Ja | `READ_MEDIA_IMAGES` (volle Freigabe) + eigene Texterkennung auf dem Gerät | Bei Teilfreigabe (`READ_MEDIA_VISUAL_USER_SELECTED`) nur die gewählten Bilder. Android liefert keinen fertigen OCR-Text anderer Apps (z. B. Google Fotos) |
| Velo-Verlauf und -Favoriten | Ja | Velo stellt einen `ContentProvider` bereit, geschützt durch die eigene Laufzeitberechtigung `io.github.davydgky.browser.permission.READ_SEARCH_DATA` | Private Tabs werden nie gespeichert und tauchen daher nie auf |

## Die Quellen im Einzelnen

### Apps
`LauncherApps.getActivityList(null, user)` für jedes Profil aus `LauncherApps.getProfiles()` liefert alle
startbaren Activities, auch die des Arbeitsprofils. Seit Android 11 filtert die Paket-Sichtbarkeit; ein
`<queries>`-Eintrag für `ACTION_MAIN`/`CATEGORY_LAUNCHER` genügt, `QUERY_ALL_PACKAGES` ist nicht nötig.
Änderungen meldet `LauncherApps.Callback` (installiert, entfernt, aktualisiert, Profil gesperrt).

Private Space (Android 15+): sichtbar nur mit `ACCESS_HIDDEN_PROFILES` und Home-Rolle. Dieser Launcher
fordert die Berechtigung bewusst nicht an. Ein gesperrter Private Space bleibt damit unsichtbar, und es
besteht keine Gefahr, dass seine Apps in Vorschlägen auftauchen. Profile im Ruhemodus
(Arbeitsprofil pausiert) werden übersprungen.

### App-Verknüpfungen
`LauncherApps.getShortcuts()` mit `FLAG_MATCH_MANIFEST | FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED |
FLAG_MATCH_CACHED` liefert statische, dynamische, angeheftete und zwischengespeicherte Verknüpfungen
(gecachte sind z. B. Unterhaltungen aus Messengern). Das klappt nur, solange die App Standard-Launcher
ist; sonst wirft die Abfrage eine `SecurityException`. Änderungen meldet
`LauncherApps.Callback.onShortcutsChanged()`. Gestartet wird über `LauncherApps.startShortcut()`.

### Zuletzt und häufig genutzte Apps
Zwei Datenquellen, die sich ergänzen:

1. **Eigenes Startprotokoll** (ohne Berechtigung): Jeder Start aus Homescreen oder Suche wird mit
   Zeitpunkt gespeichert.
2. **`UsageStatsManager.queryEvents()`** mit `PACKAGE_USAGE_STATS`: Die Berechtigung ist ein AppOp, den nur
   der Nutzer in den Einstellungen erteilen kann (`Settings.ACTION_USAGE_ACCESS_SETTINGS`). Damit zählen
   auch Starts aus Benachrichtigungen, Links und der Übersicht. Ausgewertet werden nur
   `ACTIVITY_RESUMED`-Ereignisse des eigenen Profils.

Die Vorhersage „nach Tageszeit und Häufigkeit“ rechnet der Launcher selbst: jeder Start zählt mit
einer Halbwertszeit von 14 Tagen, gewichtet nach Nähe zur aktuellen Uhrzeit (zirkulär über
Mitternacht) und danach, ob Werktag oder Wochenende passt.

### Kalender
`READ_CALENDAR`. Abgefragt wird `CalendarContract.Instances` von 30 Tagen zurück bis ein Jahr voraus,
je Termin das nächste anstehende Vorkommen (Serien erscheinen nur einmal). Änderungen meldet ein
`ContentObserver` auf `CalendarContract.CONTENT_URI`.

### Kontakte
`READ_CONTACTS`. Index aus `ContactsContract.Data`: Name, Spitzname, Firma, E-Mail, Telefonnummern
(auch als Ziffernfolge, damit „0176“ eine Nummer findet). Die in Android 17 entfernten Spalten
`ACCOUNT_NAME`/`ACCOUNT_TYPE` werden nicht benutzt. Änderungen: `ContentObserver` auf
`ContactsContract.Contacts.CONTENT_URI`.

Hinweis zur Verteilung: Für Play müsste die App ab Android 17 begründen, warum der Kontakt-Picker nicht
reicht („geräteweite Suche“). Die Plattform selbst erteilt die Berechtigung weiterhin vollständig.

### Notizen
**Android hat keine Notiz-Schnittstelle.** Die Notiz-Rolle (`ROLE_NOTES`, Android 14) regelt nur, welche
App eine neue Notiz per Stift-Taste anlegt; lesen kann man darüber nichts. AppSearch
(`GlobalSearchSession`) ist der vorgesehene Weg, über den Apps durchsuchbare Dokumente gezielt für andere
Apps freigeben. Uns ist aber keine verbreitete Notiz-App bekannt, die das für Dritt-Launcher tut.

Was tatsächlich funktioniert, sind Notiz-Apps, die ihre Notizen als **Dateien in einem frei wählbaren
Ordner** speichern. Diesen Ordner gibt der Nutzer einmal über das Storage Access Framework frei
(`ACTION_OPEN_DOCUMENT_TREE`, dauerhafte Freigabe, keine Sonderberechtigung):

| App | Schnittstelle | Anbindung |
|---|---|---|
| **Obsidian** | Markdown-Dateien im Vault-Ordner, Deep Link `obsidian://open?vault=…&file=…` | Ordner freigeben; Treffer öffnen direkt in Obsidian |
| **Markor** | Markdown-/Textdateien in einem Ordner | Ordner freigeben |
| **Logseq** | Markdown-Dateien im Graph-Ordner (`pages/`, `journals/`) | Ordner freigeben |
| **Orgzly Revived** | Org-Dateien in einem Repository-Ordner | Ordner freigeben |
| **Joplin** | Nur über die Synchronisation „Dateisystem“ (Markdown mit Metadaten) | Sync-Ordner freigeben |
| Google Keep | Keep-API nur in der Cloud und nur für Google Workspace | nicht möglich (verletzt „alles bleibt auf dem Gerät“) |
| Samsung Notes | keine Schnittstelle | nicht möglich |
| OneNote, Evernote, Notion, Simplenote | nur Cloud-APIs | nicht möglich |
| Standard Notes, Notesnook | Ende-zu-Ende verschlüsselt, keine lokale Schnittstelle | nicht möglich |

Notiz-Apps, die zuletzt geöffnete Notizen als dynamische App-Verknüpfungen veröffentlichen, erscheinen
zusätzlich automatisch über die Quelle „App-Verknüpfungen“.

### Dokumente
Seit Scoped Storage (Android 11) zeigt `MediaStore.Files` fremde Nicht-Mediendateien nur mit
`MANAGE_EXTERNAL_STORAGE`. Diesen „Zugriff auf alle Dateien“ erteilt der Nutzer in den Einstellungen
(`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`). Google Play erlaubt ihn ausdrücklich für die
Kategorie „Suche auf dem Gerät“. Der Launcher fragt danach nur, wenn der Nutzer die Quelle „Dokumente“
einschaltet.

- Dateinamen und Ordner werden sofort indiziert, Änderungen über `MediaStore.getGeneration()`
  inkrementell erkannt (`ContentObserver` auf `MediaStore.Files`).
- Den Inhalt von Text-, Markdown- und PDF-Dateien liest derselbe Hintergrundjob wie die Bilderkennung,
  also nur beim Laden. PDF-Text liefert seit Android 15 `PdfRenderer.Page.getTextContents()`, ohne
  Fremdbibliothek.
- Office-Formate (docx, xlsx, odt) werden nur über den Dateinamen gefunden.

Ohne Sonderzugriff bleibt die Ordnerfreigabe per SAF (wie bei Notizen). Sie kann aber weder `Download`
selbst noch die Speicherwurzel erfassen und ist deshalb nur die Ausweichlösung.

### Bilder mit erkanntem Text
`READ_MEDIA_IMAGES`. Android stellt den Text in Bildern nirgends bereit, also erkennt ihn der Launcher
selbst mit **ML Kit Text Recognition v2 im gebündelten Modus** (Modell in der APK, keine Google-Play-Dienste,
funktioniert offline). ML Kit würde Nutzungsstatistiken an Google melden. Der Build entfernt diesen
Meldekanal aus dem Manifest, und die CI prüft das am fertigen APK. Bilder und erkannter Text verlassen das Gerät
nie.

- Die Erkennung läuft nur **während das Gerät lädt**: ein `JobScheduler`-Job mit `setRequiresCharging(true)`.
  Beim Abstecken stoppt Android den Job, der Fortschritt bleibt je Bild gespeichert.
- Neue und geänderte Bilder erkennt `MediaStore.getGeneration()`, gelöschte ein Abgleich der IDs.
- Bei Teilfreigabe durch den Nutzer wird nur die freigegebene Auswahl erkannt.

### Velo: Verlauf und Favoriten
Velo bekommt einen `ContentProvider` (`content://io.github.davydgky.browser.searchdata/history` und
`…/bookmarks`). Er ist mit einer eigenen **gefährlichen** Berechtigung geschützt
(`io.github.davydgky.browser.permission.READ_SEARCH_DATA`). Der Nutzer erteilt sie also im normalen
Laufzeitdialog und kann sie jederzeit entziehen. Das ist bewusst keine Signaturberechtigung: Beide Apps
werden unabhängig signiert, und die Freigabe soll beim Nutzer liegen. „Lesezeichen“ sind in Velo die
Favoriten der Startseite.

Velo meldet jede Änderung über `ContentResolver.notifyChange()`. Der Launcher hält eine Kopie im eigenen
Index, damit jeder Tastendruck ohne Interprozess-Aufruf auskommt. Ein Treffer öffnet Velo direkt
(`Intent.setPackage`), auch wenn ein anderer Standardbrowser eingestellt ist.

## Architektur, die daraus folgt

1. **Launcher statt Such-App.** Verknüpfungen, die Geste auf dem Homescreen und die Kopplung der Tastatur
   an die Geste funktionieren nur als Standard-Launcher. Der Launcher ist zugleich der Prozess, den Android
   praktisch immer am Leben hält. Das trägt die Aktualisierung über `ContentObserver`.
2. **Ein Index im Speicher, eine Quelle pro Datenart.** Jede Quelle (`SearchSource`) liefert unabhängig einen
   unveränderlichen Teilindex. Eine Änderung an Kontakten baut nur den Kontaktindex neu. Abfragen laufen
   lock-frei auf dem jeweils aktuellen Stand, auf einem eigenen Such-Thread, und eine neue Eingabe
   verwirft die vorherige.
3. **Teure Daten persistent, billige frisch.** Apps, Verknüpfungen, Kontakte, Termine und Velo-Daten
   liest der Launcher beim Start neu (jeweils Millisekunden). OCR-Text, Dokument- und Notizinhalte liegen
   in einer eigenen SQLite-Datenbank, zusammen mit Generation bzw. Änderungsdatum, damit nichts doppelt
   erkannt wird.
4. **Aktualisieren bei Änderungen.** Je Quelle der passende Mechanismus: `LauncherApps.Callback`,
   `ContentObserver` (Kontakte, Kalender, MediaStore, Velo), Abgleich beim Start des Prozesses, und für
   die Texterkennung ein Job, der nur beim Laden läuft.
5. **Jede Quelle ist abschaltbar** und zeigt in den Einstellungen, welche Berechtigung fehlt und wie man
   sie erteilt. Fehlt eine Berechtigung, liefert die Quelle einfach nichts. Ein Absturz ist damit
   ausgeschlossen.
6. **Nichts verlässt das Gerät.** Die einzige Netzverbindung ist der Updater, der bei GitHub nach eigenen
   Updates sucht. Die CI prüft, dass sonst kein Code Verbindungen öffnet. Es gibt kein Backup
   (`allowBackup=false`), und der Index liegt nur im App-Speicher. „Im Web suchen“ übergibt die Eingabe
   erst auf Antippen an Velo.
7. **Reiner Suchkern.** Normalisierung, Index, Ranking und Vorhersage stecken in einem
   Android-freien Kotlin-Modul (`:core`) mit Unit-Tests. Die App-Schicht übersetzt nur zwischen Android
   und Kern.

### Zeitbudget

- **Tastatur < 150 ms nach der Geste.** Das Suchfeld hat auf dem Homescreen schon den Fokus, ohne
  Tastatur (`showKeyboardOnFocus = false`). Die Eingabeverbindung zur Tastatur-App steht also bereits,
  bevor gewischt wird. Sobald die Geste erkannt ist, übernimmt
  `WindowInsetsController.controlWindowInsetsAnimation()` die Tastatur, und sie fährt Bild für Bild mit
  dem Finger hoch. Die Suche ist eine Ebene in derselben Activity, ohne Fensterwechsel.
- **Ergebnisse < 50 ms pro Tastendruck.** Präfixsuche per Binärsuche über sortierte Token-Listen
  (O(log n) je Suchwort), Bewertung nur der Kandidaten, Icons vorgeladen.
- **Gemessen wird auf dem Gerät selbst:** Einstellungen › Messwerte zeigt Median, 95. Perzentil und
  Maximum für „Geste → Tastatur sichtbar“ und „Eingabe → Ergebnisse gezeichnet“. Jeder Messwert steht
  außerdem im Logcat (Tag `LauncherPerf`).
