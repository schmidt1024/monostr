# Monostr Android

Gradle-Projekt der Monostr-App. Modul `:monero` ist reines Kotlin/JVM ohne Android-Abhängigkeit.

## Bauen

Benötigt ein JDK 17 oder neuer (kein reines JRE). Gradle nimmt das JDK aus `JAVA_HOME`:

```bash
export JAVA_HOME=/pfad/zum/jdk-17-oder-neuer
./gradlew :monero:test
```

## Module

- `:monero` — Monero-Primitive (Adressen, Keys, Seed, URI). Pure Kotlin/JVM.
- `:tips` — Monostr-Tip-Protokoll (Events, Validierung, Watcher-Client). Pure Kotlin/JVM, hängt nur von `:monero` ab.
- `:nostr` — rust-nostr-Client, Signer (lokal, Amber/NIP-55), Feed/Thread/Profil/Publish-Repositories. Android-Bibliothek; Unit-Tests laufen auf der JVM mit `nostr-sdk-jvm`.
- `:app` — Compose-App: Login, Feed, Suche, Thread, Profil, Compose, Mitteilungen, Lesezeichen, Settings (Relays, Such-Relays, Monero-Watcher, Tip-Presets), TipSheet (eigenes Tip-Icon in der Aktionszeile, ɱ-Button im Profil), Monero-Onboarding (neue Tip-Wallet oder bestehende Wallet), Hintergrundprüfung auf neue Tips (WorkManager, 15 min), private Nachrichten (NIP-17: Tab „Nachrichten“, Chat, DM-Relays, Hintergrundprüfung mit Benachrichtigungen), Mentions (`@Name`) und Zitat-Karten, Note-Menü mit Löschantrag und gekürzten langen Notes, Zitieren und `@`-Autovervollständigung im Composer, eigenes Profil bearbeiten (kind 0), Folgen/Entfolgen im Profil (kind 3), Start-Screen, „Monostr unterstützen“ und „Über Monostr“ (Beta), Hinweise zur NIP-17-Inbox-Liste. DMs liegen lokal in einer SQLite-Datenbank pro Konto (`dms-<pubkey16>.db`, beim Abmelden gelöscht). Hilt, Navigation Compose, DataStore; nsec und Monero-View-Key im Keystore verschlüsselt.

## Suche und Lesezeichen

- Such-Tab in der unteren Leiste (Feed · Suche · Lesezeichen · Mitteilungen · Nachrichten): `npub`/`nprofile` öffnet das Profil, `note`/`nevent` den Thread. Abgeschickte Texte und Hashtags landen in „Zuletzt gesucht“ unter dem leeren Feld (bis zu 10, pro Gerät, je Zeile mit X entfernbar, „Alle löschen“); Links werden nicht gemerkt (v0.11.4). Namen und Volltext werden über NIP-50 auf eigenen Such-Relays gesucht (Default `wss://search.monostr.com` und `wss://search.nos.today`, editierbar unter Settings → „Such-Relays"; leere Liste bedeutet lokale Namenssuche ohne Netzwerk). Die Such-Relays bleiben verbunden, solange die Suche im Back-Stack ist (nicht nur, solange sie sichtbar ist), und bekommen nie die Feed-Subscriptions; später hinzugefügte normale Relays erhalten die laufenden Feed-Subscriptions.
- `#hashtag` im Suchfeld oder ein angetippter Hashtag in einer Notiz sucht über die normalen Relays. Hashtags, `nostr:`-Links und URLs im Notiztext sind antippbar.
- Private Lesezeichen: NIP-51 Liste (kind 10003), private `e`-Tags NIP-44-verschlüsselt mit dem eigenen Schlüssel. Fünftes Icon in der Aktionszeile zwischen Like und Tip; die Liste ist seit v0.11.1 der mittlere der fünf Tabs in der unteren Leiste (Feed · Suche · Lesezeichen · Mitteilungen · Nachrichten). Bestehende öffentliche Einträge anderer Clients werden angezeigt und beim Speichern erhalten. Amber-Nutzer brauchen einen Signer mit `nip44_encrypt`/`nip44_decrypt`; die Berechtigungsanfrage erscheint beim ersten Öffnen der Lesezeichen oder beim ersten Lesezeichen, nie beim Öffnen von Feed, Thread, Profil oder Suche (dort wird nur still entschlüsselt, sonst sind bis dahin nur öffentliche Einträge markiert). Eine abgelehnte Anfrage sperrt die Liste nicht, der nächste Versuch fragt erneut. Bis ein normales Relay (kein Such-Relay) einmal geantwortet hat, bleibt die Liste read-only; vor jedem Speichern wird die Liste kurz neu geholt, damit Einträge eines anderen Geräts nicht überschrieben werden; ein fehlgeschlagenes Publish überlebt nie lokal.

## Medien, Zähler und Outbox-Profil

- Medien in Notes: Bilder aus `imeta`-Tags werden inline gezeigt (maximal 4), ein Tipp öffnet den Vollbild-Viewer; Videos zeigen einen Platzhalter und spielen auf Tippen im media3-Player. Als sensibel markierte Notes (NIP-36 Content Warning oder `#nsfw`) zeigen die Bilder verschwommen, bis man tippt; abschaltbar unter Settings → „Medien".
- Zähler: Likes, Reposts und Antworten zeigen die Anzahl unter dem Icon in der Aktionszeile (NIP-45 COUNT, Maximum über alle verbundenen Relays), sobald sie größer null ist.
- Outbox im Profil: Beim Öffnen eines fremden Profils werden vorübergehend bis zu drei eigene Write-Relays dieser Person hinzugezogen, Zeile „Notes auch von …" zeigt die Domain; abschaltbar unter Settings → „Relays des Autors temporär verbinden".
- „Amber-Berechtigungen erneuern" in Settings stellt fehlende NIP-55-Berechtigungen (z. B. nach App-Update von Amber) über einen erneuten Amber-Dialog wieder her.

## Private Nachrichten (NIP-17)

- Tab „Nachrichten“ ganz rechts in der unteren Leiste (Feed · Suche · Lesezeichen · Mitteilungen · Nachrichten) mit Zähler ungelesener Nachrichten. Die DM-Synchronisation läuft ab dem Login (Live-Nachrichten und Zähler auch, bevor der Tab einmal geöffnet war); beim ersten Öffnen von Tab oder Chat wird die eigene DM-Relay-Liste (kind 10050) übernommen oder veröffentlicht. Konversationen mit Personen, denen man folgt oder denen man schon geschrieben hat, stehen oben; alle anderen unter dem aufklappbaren Trenner „Anfragen“.
- Chat mit antippbaren Links, Hashtags, `nostr:`-Verweisen und Bildern (Vollbild-Viewer wie im Feed); Status pro eigener Nachricht (Uhr = wird gesendet, Haken = gesendet, „Nicht gesendet“ mit „Erneut senden“). Button „Nachricht“ im fremden Profil öffnet den Chat.
- Versand als NIP-17 Gift Wrap (kind 14 → Seal kind 13 → Wrap kind 1059) an die DM-Relays des Empfängers (kind 10050, sonst dessen Write-Relays, immer zusätzlich `wss://relay.monostr.com`) und eine eigene Kopie an die eigenen Relays.
- Eigene DM-Relays (kind 10050) unter Settings → „DM-Relays (NIP-17)“; eine geänderte Liste startet die DM-Synchronisation neu und holt die letzten 30 Tage von den neuen Relays.
- `wss://relay.monostr.com` gibt kind 1059 nur nach NIP-42 AUTH heraus; die App authentifiziert sich automatisch (mit Amber nur still, über die gemerkte Berechtigung für kind 22242), ein fehlgeschlagenes AUTH zeigt einen Hinweis pro Relay im Tab.
- Hintergrundprüfung alle 15 min (WorkManager) mit Benachrichtigungen; Absender und Text-Vorschau sind unter Settings → „Nachrichten“ einzeln abschaltbar. Ein Tipp auf die Benachrichtigung öffnet den Chat (die Sammel-Benachrichtigung für wartende Nachrichten den Tab). Der erste Lauf nach dem Login benachrichtigt nicht; nach dem Abmelden läuft keine Prüfung mehr. DMs sind vom Android-Backup ausgeschlossen.
- Amber: braucht `sign_event` für kind 13, 22242 (AUTH) und 10050 sowie `nip44_encrypt`/`nip44_decrypt`. Wraps, die Amber nicht still entschlüsseln kann, werden geparkt; ein Tipp auf „… Nachrichten warten auf Amber“ im Tab fragt Amber einmal.
- Hinweis „… lehnt die Anmeldung ab“: das Relay hat NIP-42 AUTH abgelehnt und nennt den Grund (z. B. „restricted“). Die Ursache liegt beim Relay, nicht in der App; Nachrichten von dort lassen sich nicht laden, die anderen Relays laufen weiter. Abhilfe: das Relay unter Settings → „DM-Relays (NIP-17)“ entfernen.

## Mentions, Zitate und Profil

- `nostr:npub…`/`nprofile…` im Notiztext erscheinen als `@Name` (Primärfarbe, Tap → Profil); ohne bekanntes Profil die Kurzform, bis das Profil geladen ist. Jede Liste lädt die Namen einmal nach (ein Relay-Request pro Batch, fehlende Profile 10 min nicht erneut).
- Zitate: `nostr:note…`/`nevent…` im Text oder ein NIP-18-`q`-Tag zeigen eine Zitat-Karte (Avatar, Name, Zeit, bis 6 Zeilen, erstes Bild 16:9 mit den Medien-Regeln). Steht der Link allein auf seiner Zeile oder am Textende, verschwindet er aus dem Text; mitten im Satz bleibt er als Link „Notiz“. Eine Karte pro Notiz, keine Karte in der Karte. Geladen wird aus der lokalen DB, sonst von den Hint-Relays und den normalen Relays (5 s); nicht gefundene Notizen zeigen „Notiz nicht gefunden“ und werden 10 min nicht erneut gesucht.
- Zitieren: Repost-Icon → Sheet „Reposten“ / „Zitieren“. Der Composer zeigt die Karte; gesendet wird der Text + `nostr:nevent…` mit `q`- und `p`-Tag (leerer Text erlaubt). Relay-Hint ist das erste verbundene normale Relay.
- `@`-Autovervollständigung im Composer: Follows und lokal gespeicherte Profile (kein Netz beim Tippen), höchstens 8. Die Auswahl setzt `nostr:npub…` in den Text (angezeigt als `@Name`); jede Erwähnung wird beim Senden ein `p`-Tag.
- Eigenes Profil: Avatar oben rechts im Feed → Profil (mit Banner) → „Profil bearbeiten“: Banner- und Bild-URL (nur `https://`, kein Upload), Anzeigename, Name, Bio, NIP-05. Gespeichert wird kind 0 als Merge: nur diese sechs Felder ändern sich, alle anderen Schlüssel (z. B. Lightning-Adressen) bleiben. Lokal gespeichert wird erst, wenn ein Relay das Event angenommen hat. Amber braucht dafür `sign_event` kind 0 (Teil der Login-Berechtigungen).

## Note-Menü, Löschen, lange Notes

- Dreipunkt-Menü in jeder Note: „Link kopieren“ (`nostr:nevent` mit Autor und Relay-Hinweisen), „Text kopieren“, bei eigenen Notes „Löschen beantragen“. Das fragt nach und sendet einen Löschantrag (NIP-09, kind 5 mit `e`- und `k`-Tag); lokal gespeichert wird er erst nach einem Relay-OK, und erst er entfernt die Note aus der lokalen Datenbank. Bilder der Note, die auf dem eigenen Media-Server liegen, werden dort mitgelöscht (best effort), aber nur, wenn keine andere eigene Note und nicht das eigene Profil sie noch zeigt. Relays dürfen den Antrag ignorieren.
- Löschanträge anderer werden in Feed, Thread und Profil geholt; eine zurückgezogene Note verschwindet, auch wenn das Gerät sie erst nach dem Antrag bekommt.
- Stummschalten (NIP-51 kind 10000, nur `p`-Einträge, privat NIP-44-verschlüsselt im Inhalt; fremde öffentliche Einträge und andere Tags bleiben erhalten): Note-Menü „<Name> stummschalten“ mit „Rückgängig“, Profil-Menü, Einstellungen → „Stummgeschaltete Konten“ (dort wieder aufheben). Stumme Konten verschwinden aus Feed, Thread, Suche, Lesezeichen, Mitteilungen, Nachrichten und Zitat-Karten; ihr Profil, ihre Tips und Zähler bleiben sichtbar. Ist der private Teil einer bestehenden Liste nicht lesbar, ist die Liste nur lesbar. Amber braucht `sign_event` für kind 10000 sowie `nip44_encrypt`/`nip44_decrypt` (Einstellungen → „Amber-Berechtigungen erneuern“ holt fehlende nach).
- Notes mit mehr als zwölf Zeilen werden in Listen auf zehn gekürzt, „Mehr anzeigen“ klappt sie an Ort und Stelle auf (nicht dauerhaft; die Note im Thread ist immer ganz). Ein Tipp auf die Wortmarke oder den Feed-Tab im Feed scrollt nach oben und lädt neu.
- Profil bearbeiten: Profilbild und Banner lassen sich entfernen; das vorherige Bild verlässt den eigenen Media-Server, wenn keine eigene Note und nicht das Profil (Bild bzw. Banner) es noch zeigt.
- Test: `NoteMenuTest` (Emulator, braucht Netz: veröffentlicht eine Note und ihren Löschantrag mit einem frischen Wegwerf-Schlüssel an die Standard-Relays).

## Folgen, Start, Unterstützen und DM-Härtung

- Folgen: Button „Folgen“ neben „Nachricht“ im fremden Profil; „Folge ich“ fragt vor dem Entfolgen. Geschrieben wird die eigene kind 3 nur auf einer aktuellen Liste: erst ein Relay-Fetch (5 s); eine lokale Liste zählt nur, wenn ein Relay sie vor weniger als 5 min geliefert hat. Sonst „Folgeliste konnte nicht geladen werden“ und nichts wird geschrieben. Alle anderen Tags und der `content` (Relay-Liste älterer Clients) bleiben unverändert; lokal gespeichert wird erst nach einem Relay-OK. Ein neues Konto ohne kind 3 bekommt eine Liste mit genau einem `p` nur, wenn der Fetch vor dem Timeout zurückkam, alle konfigurierten Relays davor und danach verbunden waren und keines abgelehnt hat (CLOSED); ist ein Relay der Liste nicht erreichbar, bleibt es bei „Folgeliste konnte nicht geladen werden“. Danach lädt der Feed neu.
- Start-Screen: der System-Splash zeigt nur die Hintergrundfarbe, danach Logo (Mitte bei 38,2 % der Höhe) und Wortmarke; ein Spinner erscheint erst nach 1,5 s.
- Settings → „Monostr unterstützen“: Monero-Button (`monero:`-URI an die Wallet-App, ohne Wallet Adresse und QR-Code), Mitwirkende (`app/src/main/assets/contributors.txt`, neu schreiben mit `./gradlew :app:updateContributors`), Quellcode-Link. Im Feed erscheint frühestens 14 Tage nach dem ersten Login einmal „Gefällt dir Monostr?“; „Ausblenden“ oder ein Besuch der Seite beendet das für immer.
- Follower-Zahlen im Profil: eigene kind 3 für „Folge ich“, NIP-45 COUNT der Relays und Primals Index (`cache2.primal.net`) für Follower; Primal ist ein proprietärer Dienst und lässt sich unter Settings → „Follower-Zahlen über Primal“ abschalten (Standard an, v0.13.3), dann zählen nur die Relays.
- Settings → „Über Monostr“: Version und Build, „Beta“ bis 1.0, Feedback (Projektprofil auf Nostr), „Problem melden“ (Issues des Repos), Lizenz. Die Werte stehen in `android/gradle.properties` (`MONOSTR_SUPPORT_ADDRESS`, `MONOSTR_FEEDBACK_NPUB`, `MONOSTR_REPO_URL`, `MONOSTR_LICENSE`); ein leerer Wert blendet die Zeile aus.
- DM-Relays: Karten „relay.monostr.com fehlt in deiner Inbox-Liste“ (Hinzufügen; bei vier Relays Auswahl, welches ersetzt wird) und „Ein zweites Inbox-Relay erhöht die Zuverlässigkeit“ (Vorschläge mit NIP-17 und Pflicht-AUTH laut NIP-11); beide pro Konto einmal wegwischbar.
- DM-Härtung: lehnt ein eigenes Inbox-Relay AUTH ab, bleibt der Sync-Stand stehen; nach erfolgreicher AUTH verschwindet der Hinweis und die Runde wird einmal nachgeholt. Ein Listenwechsel verwirft laufende Runden. Wraps, die Amber still nicht öffnen kann, werden nach drei Versuchen nur noch auf Tipp versucht; die Sammel-Benachrichtigung „N Nachrichten warten auf Amber“ kommt einmal und erst nach geheilter AUTH wieder. Wraps, die nie eine Nachricht werden, stehen mit Grund in der DM-DB und werden nicht erneut entschlüsselt. Das Relay hält Gift-Wraps 30 Tage (`relay/ops/relay.cron`).

## Tips an Profile, anonyme Tips

- ɱ-Button im Profil (zwischen „Folgen“ und „Nachricht“): tippt die Person ohne Note-Bezug (Protokoll 0.2: Intent ohne `e`). Das Profil zeigt keine Summe und keine Tipper-Liste. Der Button färbt sich, solange ein eigener Tip auf sein Receipt wartet; kommt es an, erscheint „Tip angekommen“. Dafür fragt die App Receipts an die Person nur ab, solange ein eigener Profil-Tip aussteht und das Profil sichtbar ist – bei einem anonymen Tip über eine eigene Verbindung zu den Relays des Watchers (ein Live-Abo: das Receipt erscheint, sobald der Watcher es veröffentlicht, solange diese Verbindung steht), nie über die Hauptverbindung.
- Ein Tip ist nur ein Betrag: kein Kommentarfeld. Kommentare älterer Tips oder anderer Clients zeigt die Tipper-Liste weiter.
- „Anonym tippen“ im TipSheet (Voreinstellung an, die letzte Wahl bleibt gemerkt): der Intent trägt `anon`, wird mit einem Einmal-Schlüssel signiert und geht über eine eigene Verbindung ohne NIP-42-Anmeldung nur an die Relays des Watchers. Er steht nie in der Nostr-Datenbank der App und läuft nie über die Hauptverbindung; für ein erneutes Senden liegt das signierte Event im Pending-Store. Das Receipt nennt dann keinen Sender; Empfänger und Tipper-Liste zeigen „Anonym“. Sind die Relays des Watchers nicht bekannt (Watcher nicht erreichbar), meldet das Sheet das und sendet nichts – auch keinen öffentlichen Tip. Die IP-Adresse sieht das Relay weiterhin (VPN/Tor). Ein Intent, den ein Watcher-Relay angenommen hat, wird nicht erneut gesendet.
- Benachrichtigungen: ein Profil-Tip öffnet das Profil des Senders, ein anonymer Profil-Tip nichts.
- Das offene TipSheet zeigt „Tip angekommen“ mit Häkchen, sobald der ausstehende Tip beglichen ist. Es fragt dafür selbst kein Relay, sondern beobachtet nur den lokalen Pending-Store (den der Screen dahinter räumt). Eigene Notes haben ein deaktiviertes Tip-Icon.
- Einrichtung: die Payment-Info (kind 10037) bleibt lokal nur gespeichert, wenn ein Relay sie angenommen hat. Scheitert die Veröffentlichung nach der Registrierung beim Watcher, wird die Registrierung zurückgenommen: bei einem Wallet-Wechsel am selben Watcher wird die bisherige Wallet wieder registriert; bei einer ersten Einrichtung oder einem Wechsel zu einem neuen Watcher wird das neue Konto abgemeldet (der alte Watcher wird erst nach erfolgreicher Veröffentlichung abgemeldet); nennen die Relays diesen Watcher noch für das Konto (nach Abmelden, zweites Gerät), bleibt das Konto bestehen.

## Relay-Verbindungen nach einer Pause

- Kommt die App aus dem Hintergrund zurück und ist kein Relay mehr verbunden, verbindet sie sofort neu (`MainActivity.onStart` → `NostrEngine.reconnectIfLost`), statt auf den nächsten Versuch von rust-nostr zu warten. Dasselbe passiert vor jedem Veröffentlichen: ohne verbundenes Relay wird erst neu verbunden (bis 5 s), dann gesendet. Vorher scheiterte ein Senden in dieser Lücke nach 10 s, obwohl die Relays erreichbar waren (Monero-Einrichtung direkt nach dem Kopieren des Seeds).
- Der erzwungene Neuaufbau beendet die Wartezeit eines Relays (`disconnect`), verbindet (`tryConnect`), fragt die im Relay gespeicherten Abos erneut an und gibt Relays, die noch fehlen, mit `connect()` an den Pool zurück. Er läuft als Ganzes nicht abbrechbar: ein mitten im Verbindungsaufbau abgebrochenes `tryConnect` ließe das Relay für immer im Zustand CONNECTING. Solange irgendein Relay verbunden ist, passiert nichts.
- Ist mindestens ein Relay verbunden, geht ein Event nur an die verbundenen; ein nicht verbundenes Relay gilt sofort als fehlgeschlagen (`not connected`), statt das Senden rund 10 s aufzuhalten. Ist keines verbunden, wird erst neu verbunden und dann an alle gesendet. Auf ein Relay, das gerade erst verbindet (nach dem Start, nach der Rückkehr aus dem Hintergrund), wartet das Senden bis zu 2 s: sonst läge ein ersetzbares Event (Follow-Liste, Profil, Payment-Info) nur auf dem einen schnellen Relay, und niemand reicht es den anderen nach. Ein Relay, dessen Verbindungsaufbau hängt, kostet damit jedes Senden bis zu 2 s.
- Bekannt, nicht behoben: Inbox-Relays außerhalb der normalen Liste werden nicht geweckt; halboffene Verbindungen (als verbunden gemeldet, aber tot) erkennt die App nicht; `sendTo` (zusätzliche Relays wie die des Watchers) wartet weiter auf ein totes Ziel.

## Relay-Liste (NIP-65) und Profile von anderswo

- Einstellungen → Relays → „Als meine Relay-Liste veröffentlichen (NIP-65)“ ersetzt die kind-10002-Liste des Kontos durch die Relays der App. Die App legt von sich aus nur dann eine Liste an, wenn es keine gibt; eine Liste aus einem anderen Client bleibt sonst stehen – und der Tip-Watcher wie andere Clients suchen das Konto dann auf Relays, die es nicht mehr nutzt. Lokal gespeichert wird die neue Liste erst nach einem Relay-OK. Danach geht sie zusätzlich an die Index-Relays (`relay.monostr.com`, `purplepag.es`), auf denen andere Clients und der Watcher Relay-Listen suchen. Alle Relays der App stehen ohne Markierung in der Liste (Lesen und Schreiben); die alte Liste wird nirgends aufbewahrt, deshalb fragt die App vor dem Ersetzen nach.
- Profile (kind 0), die auf keinem eigenen Relay liegen, sucht die App einmal auf dem Profil-Index `wss://purplepag.es` (für die Dauer der Abfrage angehängt, danach wieder gelöst; nicht gefundene Profile 10 min nicht erneut). Das gilt auch für den Profil-Editor, damit ein nur anderswo veröffentlichtes eigenes Profil beim Speichern seine fremden Felder behält. Der Profil-Editor wartet auf diese Abfrage (bis 3 s), ein geöffnetes Profil ebenso (bis 4 s), sofern nicht in den letzten 10 min schon eine Liste danach gefragt hat – dann füllt es sich, sobald die Antwort da ist. Listen (Feed, Suche, Benachrichtigungen, Thread, Tipper-Liste) warten nicht: dort läuft die Abfrage im Hintergrund, und der Name erscheint beim nächsten Aufbau der Liste.
- Der Index-Betreiber sieht, nach welchen Profilen dieses Gerät fragt – auch nach dem eigenen, bei jedem Start, solange es auf keinem eigenen Relay liegt. Verlangt der Index eine Anmeldung (NIP-42), meldet sich die App dort wie bei jedem Relay mit dem Konto an; die Abfragen sind dann dem Konto zugeordnet.

- Drei Aktionen auf die ganze Relay-Liste stehen in den Einstellungen untereinander, jede mit Rückfrage (`RelayListActions`): „Diese Relays als meine Relay-Liste veröffentlichen“ (App → Profil), „Diese Relays durch meine veröffentlichte Liste ersetzen“ (Profil → App) und „Standard-Relays wiederherstellen“ (nur sichtbar, wenn die Liste von den Standard-Relays abweicht). Anlass (v0.8.5): die beiden ersten standen weit auseinander unter ähnlichen Namen; das Übernehmen einer alten Liste aus einem anderen Client brachte 20 tote Relays in die App. Das Zurücksetzen kennt die Relays des Watchers nicht: liegen sie außerhalb der Standard-Relays, kommen sie erst wieder hinzu, wenn die Monero-Einrichtung (oder der Watcher-Wechsel) erneut durchlaufen wird; bis dahin können Receipts fehlen.

## Bilder hochladen

- Composer: Bild-Knopf unter dem Textfeld öffnet die System-Fotoauswahl (keine Berechtigung nötig), bis zu vier Bilder. Der Upload beginnt bei der Auswahl; Senden ist gesperrt, solange ein Bild lädt oder gescheitert ist. Die URLs stehen am Ende der Note, je Bild ein `imeta`-Tag (`url`, `m`, `x`, `size`, `dim`, `blurhash`). Der Schalter „Sensibler Inhalt“ setzt `content-warning`.
- Profil bearbeiten: „Bild wählen“ unter Banner- und Bild-URL lädt ein Bild hoch und trägt die URL ein.
- Vor dem Upload wird das Bild auf dem Gerät aufrecht gedreht, verkleinert (Note 2048 px, Banner 1500 px, Profilbild 800 px lange Kante) und neu kodiert; dabei gehen alle Metadaten verloren (Standort, Gerät, Zeit). GIF werden unverändert hochgeladen.
- Server: Standard `https://media.monostr.com` (Blossom, siehe `media/README.md`), änderbar unter Einstellungen → Medien → Medien-Server. Der eigene Server nimmt keine Inhalte für Erwachsene an.
- Ein entferntes Bild und ein ohne Senden verlassener Composer löschen den Upload am Server (best effort) – aber nur, wenn das Bild erst durch diesen Upload auf den Server kam: die App fragt vor dem Upload per `HEAD /<sha256>` nach, und ein Bild, das schon dort lag (etwa aus einer früheren Note), wird nie gelöscht. Nach dem Senden gibt es in der App kein Löschen.
- Amber: die App bittet um `sign_event` für kind 24242 (Upload) und kind 5 (Löschantrag, beim ersten Löschen einer eigenen Note). Wer vor 0.9.0 angemeldet war, tippt einmal Einstellungen → „Amber-Berechtigungen erneuern“.
- Tests: `MediaUploadTest`, `ImagePreparerTest`, `MediaServerSettingTest` (Emulator, ohne Netz; Media-Server und Relay laufen im Testprozess auf 127.0.0.1). Der Debug-Build erlaubt dafür Klartext zu 127.0.0.1, der Release-Build nicht.

## App bauen und auf dem Gerät testen

```bash
export JAVA_HOME=/pfad/zum/jdk-17-oder-neuer
./gradlew :app:assembleDebug                # APK unter app/build/outputs/apk/debug/
./gradlew test                              # JVM-Tests aller Module
./gradlew :app:lintDebug                    # Lint; fehlende oder überzählige Übersetzungen sind Fehler
./gradlew :app:connectedDebugAndroidTest    # Gerätetests: Login-Smoke, TipSheet (Compose, inkl. deutscher Konfiguration), Tip-Flow, Profil-Tip gegen ein Loopback-Relay (`ProfileTipTest`), Suche, Lesezeichen, DM-Datenbank, DM-Flow, Zitat-Karten (`QuoteCardTest`), Mention-Autovervollständigung (`MentionComposeTest`), Start-Screen (`StartScreenTest`), Unterstützen/Über (`SupportScreenTest`, `AboutScreenTest`), Folgen gegen ein Loopback-Relay (`FollowTest`), Profil bearbeiten gegen relay.monostr.com (`ProfileEditTest`, braucht Netz), Relay-Listen-Aktionen mit Rückfrage (`RelayListActionsTest`), Note-Menü, Löschen und gekürzte Notes (`NoteMenuTest`, braucht Netz), Stummschalten mit Rückgängig, Einstellungen-Liste und stummes Profil (`MutedTest`, braucht Netz), Bild-Upload gegen einen Media-Server im Testprozess (`MediaUploadTest`, `ImagePreparerTest`, `MediaServerSettingTest`)
```

Die Gerätetests laufen zuerst auf dem Emulator (AVD `monostr`, API 35), das Pixel 7a dient als Abnahme. Sie brauchen keine Wallet-App und keinen erreichbaren Watcher; der Tip-Flow-Test stellt die Relays auf einen unerreichbaren Loopback-Port, ebenso `SearchTest` (läuft offline). `BookmarkTest` veröffentlicht dagegen live und braucht Netzwerk, ebenso `DmFlowTest` (zwei frische Keys pro Lauf schreiben sich über `wss://relay.monostr.com` mit AUTH); `FollowTest` liest und schreibt nur gegen ein Relay auf `127.0.0.1` im Testprozess (der Such-Tab verbindet dabei die Standard-Such-Relays). Der manuelle Stagenet-End-to-End-Test steht in `docs/testing/stagenet-e2e.md`.

Ein aktiver Autofill-Dienst (z. B. Bitwarden) kann während `connectedDebugAndroidTest` den Fensterfokus an sich ziehen (`RootViewWithoutFocusException`); vor dem Lauf abschalten und danach wieder herstellen:

```bash
adb shell settings put secure autofill_service null
# … Tests laufen lassen …
adb shell settings put secure autofill_service com.x8bit.bitwarden/com.x8bit.bitwarden.Autofill.AutofillService
```

## Sprachen und Theme

- Quelle der UI-Texte ist Englisch in `app/src/main/res/values/strings.xml`. Übersetzungen liegen in `values-de`, `values-es`, `values-ru`, `values-tr`, `values-pt`, `values-fr` und `values-it`; Einträge mit `translatable="false"` werden nicht übersetzt.
- Fachbegriffe, Anrede und Stil pro Sprache stehen im Glossar `docs/i18n/glossary.md`.
- Prüfungen: `python3 scripts/check-ui-literals.py` (keine UI-Literale im Kotlin-Code), `python3 scripts/check-strings.py` (jede Sprache hat alle Schlüssel, keine überzähligen, gleiche Platzhalter) und Lint (`MissingTranslation`/`ExtraTranslation` sind Fehler).
- Neue Sprache: `values-xx/strings.xml` anlegen und `xx` in `localeFilters` (`app/build.gradle.kts`) sowie in `LOCALES` von `scripts/check-strings.py` ergänzen. Die Per-App-Sprachliste (`generateLocaleConfig`) entsteht daraus automatisch; Code ändert sich nicht.
- Schriften: Inter (Text) und JetBrains Mono (Adressen, Beträge, Seed), beide unter der SIL Open Font License; Lizenztexte unter `app/src/main/assets/licenses`, Subsets erzeugt `scripts/subset-fonts.sh`.
- Notes in Listen sind wie bei X eingerückt (Avatar in eigener Spalte, Name · Zeit, Text, Bilder und Aktionen daneben); die im Thread geöffnete Note nimmt die volle Breite, mit größerem Text, Handle (NIP-05 oder npub) und ausgeschriebener Zeit; der Thread öffnet mit ihr im Bild (v0.11.5).
- Theme-Modus (System, Hell, Dunkel) und Akzentfarbe stellt man in Settings → Darstellung ein; ein Akzent wird so weit zur Textfarbe des Modus gemischt, dass er im Hellen 5,5:1 und im Dunkeln 7:1 gegen die Fläche erreicht (Material-3-Praxis: Ton 40 bzw. Ton 80) – im Dunkeln heller, im Hellen dunkler als sein Seed; die Vorschau-Punkte zeigen die gemischte Farbe des aktiven Modus (v0.11.3); ein siebter Akzent „Eigene“ wird über einen Farbton-Regler gewählt (Sättigung und Helligkeit fest, dieselbe Mischung; v0.11.4); die App-Sprache folgt der Systemeinstellung bzw. der Per-App-Sprache von Android.

Toolchain: AGP 9.4.1 mit eingebautem Kotlin 2.3.21, compileSdk 37, minSdk 26. Das SDK steht in `local.properties` (`sdk.dir=…`), fehlende Plattformen lädt Gradle nach.

## Release-Build

Signiert wird mit einem Keystore **außerhalb des Repos**. Einmalig anlegen (Passwort nie ins Repo, nie in Logs):

```bash
mkdir -p ~/.monostr && chmod 700 ~/.monostr
keytool -genkeypair -keystore ~/.monostr/release.jks -alias monostr -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Monostr, O=Monostr, C=CH"        # fragt Store- und Key-Passwort ab
```

Dann in `~/.gradle/gradle.properties` (Rechte 600):

```
MONOSTR_STORE_FILE=/home/<user>/.monostr/release.jks
MONOSTR_STORE_PASSWORD=…
MONOSTR_KEY_ALIAS=monostr
MONOSTR_KEY_PASSWORD=…
```

`./gradlew clean :app:assembleRelease --no-build-cache` baut `app/build/outputs/apk/release/app-release.apk` (R8, Ressourcen-Shrinking,
nur arm64-v8a + armeabi-v7a). Fehlen die Properties, entsteht eine unsignierte APK. Prüfen:
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

Version steht in `version.properties` (`versionName`, `versionCode` = major·10000 + minor·100 + patch, z. B.
0.13.2 → 1302; bis 0.13.1 war es die Commit-Zahl, höchstens 518). Release: beide Werte erhöhen, committen, Tag
`vX.Y.Z` auf diesen Commit auf `main`, dann bauen.

Reproduzierbar: Ein Release-Build aus einem frischen Klon (auch flach, anderer Pfad) ist byte-identisch mit
unserem, solange er `clean` und ohne Build-Cache läuft — ein inkrementeller Build oder Cache-Einträge aus einem
solchen (`org.gradle.caching=true`) weichen in `classes.dex` und `baseline.prof` ab. Die APK enthält weder den Commit-Hash (`vcsInfo`) noch den für Google verschlüsselten
Abhängigkeitsblock (`dependenciesInfo`); beides würde einen Build aus dem öffentlichen Export unterscheiden.

**Keystore und Properties sichern.** Geht der Keystore verloren, lassen sich installierte Apps nicht mehr
aktualisieren (neuer Key = neue App-Identität).

R8-Regeln stehen in `app/proguard-rules.pro` (JNA, rust-nostr/UniFFI, kotlinx.serialization). Nach Änderungen an
Abhängigkeiten die Release-APK auf dem Gerät durchklicken: Login, Feed, TipSheet, Settings – R8-Fehler zeigen sich
erst zur Laufzeit.
