# Monostr Tips – Monero-Zahlungen für Nostr-Notes und -Profile

Status: Draft
Version: 0.6

## Zusammenfassung

Dieses Dokument beschreibt vier Event-Kinds (10037, 9738, 9739, 10038), mit
denen ein Nutzer eine Nostr-Note oder ein Profil mit einer Monero-Zahlung ("Tip")
bedenken kann, und einen Watcher, der eingehende Zahlungen für Empfänger erkennt
und quittiert: entweder als HTTP-Dienst für registrierte Empfänger oder als
persönlicher Watcher des Empfängers, der nur über Relays erreichbar ist.

Beträge sind ganzzahlig in Piconero (1 XMR = 10^12 Piconero).

## Rollen

- **Empfänger**: veröffentlicht seine Monero-Hauptadresse und den Watcher,
  dem er vertraut (Payment-Info).
- **Sender**: kündigt eine Zahlung an (Tip-Intent) und zahlt mit einer
  beliebigen Monero-Wallet an eine Integrated Address.
- **Watcher**: hält den privaten View-Key des Empfängers (Datenminimierung: sonst nur
  Pubkey, Adresse, Intents, Receipts; keine IP-Logs; Abmelden löscht das Watcher-Konto,
  der View-Key bleibt im Scanner inaktiv bis zum manuellen Entfernen – Referenz-Implementierung
  siehe `watcher/README.md`), erkennt die
  Zahlung und veröffentlicht eine signierte Quittung (Tip-Receipt).

## Payment-Info – kind 10037 (replaceable)

Autor: Empfänger.

```json
{
  "kind": 10037,
  "content": "",
  "tags": [
    ["address", "4..."],
    ["watcher", "https://watcher.example", "<watcher-hex-pubkey>"],
    ["network", "mainnet"],
    ["amounts", "hidden"]
  ]
}
```

- `address`: Monero-Standardadresse (Mainnet `4…`, Stagenet `5…`). Keine
  Subadresse, keine Integrated Address.
- `watcher`, zwei Formen, unterschieden am ersten Feld:
  - URL-Form `["watcher", "https://watcher.example", "<watcher-hex-pubkey>"]`:
    Watcher mit HTTP-API (unten), Relays aus `GET /v1/info`.
  - Pubkey-Form `["watcher", "<watcher-hex-pubkey>", "wss://relay1", …]` (ab 0.4):
    persönlicher Watcher ohne HTTP-API; 1 bis 4 Relays (`wss://`, ohne
    Userinfo/Query/Fragment). Kein Relay, mehr als 4 oder ein Nicht-`wss`-URL
    machen das Event ungültig (= keine Tips). Clients bis 0.3 lesen das erste Feld
    als URL, scheitern und zeigen den Empfänger ohne Tips an.
- `network`: `mainnet` oder `stagenet`.
- `amounts` (optional, ab 0.5): `["amounts", "hidden"]` heißt, der Empfänger
  will keine öffentlichen Beträge. Fehlt das Tag oder hat es einen anderen
  Wert, sind Beträge öffentlich, wie in 0.4; bestehende Events und Clients
  funktionieren so unverändert weiter, und weitere Werte bleiben möglich.
  Ein Empfänger setzt `hidden` nur, wenn sein Watcher `protocol` ≥ 0.5
  meldet (siehe Watcher-HTTP-API und Watcher-Status); ein älterer Watcher
  verwirft jeden Intent ohne `amount`, und es kämen keine Receipts.
- Ein Event ohne `address`-Tag bedeutet: Empfänger nimmt keine Tips an.
- Der private View-Key erscheint nie in einem Nostr-Event.

## Tip-Intent – kind 9738 (regular)

Autor: Sender.

```json
{
  "kind": 9738,
  "content": "optionaler Kommentar",
  "tags": [
    ["e", "<note-id>"],
    ["p", "<empfänger-pubkey>"],
    ["amount", "5000000000"],
    ["pid", "a1b2c3d4e5f60718"],
    ["pid_nonce", "000102030405060708090a0b0c0d0e0f"],
    ["type", "tip"],
    ["expiration", "<unix-timestamp>"]
  ]
}
```

- `amount` (ab 0.5 optional): Betrag in Piconero. Der Sender MUSS `amount`
  weglassen, wenn die Payment-Info des Empfängers `["amounts", "hidden"]`
  enthält, und MUSS es sonst angeben (Watcher vor 0.5 verwerfen Intents ohne
  `amount`). Ein Watcher ab 0.5 akzeptiert Intents mit und ohne `amount`. Ein
  vorhandenes, aber ungültiges `amount` (keine positive ganze Zahl) macht den
  Intent weiterhin ungültig.
- `e` ist optional. Mit `e` gilt der Tip einer Note, ohne `e` dem Profil des
  Empfängers aus `p`. Ein vorhandenes, aber ungültiges `e` (nicht 64
  Hex-Zeichen klein) macht den Intent ungültig; er gilt nicht als Profil-Tip.
- `pid`: 8 Byte, 16 Hex-Zeichen. Wird zur Payment-ID der Integrated Address.
  Ab 0.6 leitet der Sender `pid` aus seinem Signaturschlüssel ab (siehe
  `pid_nonce`); Sender vor 0.6 MUSSTEN `pid` aus einem kryptografisch
  sicheren Zufallsgenerator (CSPRNG) erzeugen und für jeden Intent neu ziehen. Der Watcher ordnet eine
  Zahlung allein über `pid` einem Intent zu und akzeptiert pro Empfänger und
  `pid` nur den ersten Intent. Wer eine `pid` vorhersagen kann, kann deshalb
  vor dem echten Sender einen eigenen Intent veröffentlichen und dessen
  Zahlung für sich quittieren lassen.
- `pid_nonce` (ab 0.6, Sender MUSS es setzen): 16 Byte aus einem CSPRNG, 32
  Hex-Zeichen klein, für jeden Intent neu. Damit gilt
  `pid = hex(sha256(utf8("monostr-tips/pid") || pubkey || nonce)[0..8])`, wobei
  `pubkey` die 32 Byte des Schlüssels sind, der den Intent signiert (bei
  `anon` der Einmal-Schlüssel), und `nonce` die 16 Byte aus `pid_nonce`. Ein
  Intent mit fehlerhaftem `pid_nonce` oder mit einer `pid`, die nicht so
  entsteht, ist ungültig. Wer eine fremde `pid` kopiert, kann sie unter dem
  eigenen Schlüssel nicht gültig machen. Ein Intent ohne `pid_nonce` heisst
  ungebunden (Verhalten vor 0.6) und bleibt gültig.
  Ein Watcher ab 0.6 bevorzugt pro `pid` den gebundenen Intent: Kommt ein
  gebundener Intent nach einem ungebundenen mit derselben `pid`, ersetzt er
  ihn, solange dieser noch offen ist und kein Receipt hat. Ein ungebundener
  Intent ersetzt nie einen gebundenen; unter ungebundenen bleibt der erste.
  Watcher vor 0.6 ignorieren `pid_nonce` und ordnen weiter über `pid` zu.
- `type`: `tip` (eigenständiger Tip, Standard in Monostr), `like` oder
  `boost` (Tip, den der Client an einen Like bzw. Repost koppelt). Der Wert
  dient nur der Anzeige: Clients können gekoppelte Tips am Like- bzw.
  Repost-Button zählen statt als eigenständigen Tip. Fehlt `type`, gilt `tip`;
  ein vorhandener, aber unbekannter Wert macht den Intent ungültig. Watcher
  kopieren den Wert unverändert in das Receipt. Sender und Watcher schreiben
  `type` weiterhin immer, weil Implementierungen vor Version 0.3 das Tag
  verlangen.
- `expiration` (NIP-40): empfohlen `created_at + 86400`. Ab 0.5.1 verwirft
  ein Watcher Intents mit `expiration` mehr als 48 Stunden nach `created_at`.
- `["anon"]` (optional): der Sender will nicht genannt werden. Ein solcher
  Intent ist mit einem Einmal-Schlüssel signiert, der nur für diesen Intent
  erzeugt und danach verworfen wird, und geht nur an die Relays des Watchers,
  nicht an die Write-Relays des Senders.
- `content`: optionaler Kommentar. Monostr sendet ihn leer.
- Watcher ignorieren unbekannte Tags und kopieren sie nicht ins Receipt.
  Zusätzliche Bedeutung eines Tips steht im Intent; Clients lesen sie über die
  `intent`-Referenz des Receipts.

Der Sender bildet die Integrated Address aus der Hauptadresse des Empfängers
und `pid`:

```
integrated = base58_monero(netbyte || spend_pub || view_pub || pid || keccak256(netbyte || spend_pub || view_pub || pid)[0..4])
```

Netbyte Integrated: Mainnet 19 (0x13), Stagenet 25 (0x19).

Zahlung per URI: `monero:<integrated>?tx_amount=<xmr-dezimal>&tx_description=<text>`.

Der Intent wird auf die Write-Relays des Senders und auf die Relays des
Watchers veröffentlicht: URL-Form aus `GET /v1/info`, Pubkey-Form aus dem
`watcher`-Tag. Ab 0.5.1 gehen die Relays des Watchers zuerst: Die `pid` steht
offen im Intent, und der Watcher behält pro `pid` den ersten Intent. Wer die
Write-Relays des Senders liest, soll die `pid` nicht in einen eigenen Intent
kopieren und vor dem echten beim Watcher sein können (die Bindung der `pid`
an den Sender folgt in 0.6). Ein Client nutzt höchstens 4 Relays aus
`GET /v1/info`.

Grenzen ab 0.5.1 (Watcher): höchstens 500 offene Intents pro Empfänger und
höchstens 20 pro Empfänger und Sender; darüber hinaus wird ein Intent
verworfen.

## Tip-Receipt – kind 9739 (regular)

Autor: Watcher.

```json
{
  "kind": 9739,
  "content": "",
  "tags": [
    ["e", "<note-id>"],
    ["p", "<empfänger-pubkey>"],
    ["P", "<sender-pubkey>"],
    ["amount", "5000000000"],
    ["intent", "<intent-event-id>"],
    ["type", "tip"]
  ]
}
```

- `e` steht genau dann im Receipt, wenn der Intent eines hatte. Ein Receipt
  ohne `e` zählt für keine Note.
- `P` steht genau dann im Receipt, wenn der Intent kein `anon`-Tag hatte. Ein
  Receipt ohne `P` ist ein anonymer Tip.
- `amount`: tatsächlich empfangener Betrag. Das Tag steht genau dann im
  Receipt, wenn der Intent ein `amount` hatte. Ein Receipt ohne `amount` ist
  gültig und zählt als Tip, trägt aber zu keiner Summe bei.
- `type`: wie im Intent. Fehlt `type`, gilt `tip`.
- Kein txid.
- Mehrere Zahlungen mit derselben `pid` erzeugen je ein Receipt mit
  derselben `intent`-Referenz.
- Der Watcher veröffentlicht auf seinen Relays und den Write-Relays des
  Empfängers (NIP-65). Ab 0.5.1 nur auf `wss://`-Relays mit öffentlicher
  Adresse; `ws://`, Namen ohne Punkt und lokale Endungen (`.local`, `.lan`, …)
  übergeht er.
- Ab 0.5.1 quittiert ein Watcher nur Zahlungen in Blöcken ab der Höhe, die er
  selbst sah, als er den Intent zum ersten Mal speicherte (abzüglich 30 Blöcke
  Spielraum), oder im Mempool. Ältere Zahlungen mit derselben `pid` erzeugen
  kein Receipt: Sonst könnte ein neuer Intent mit einer kopierten `pid` eine
  frühere Zahlung für sich beanspruchen. `created_at` des Intents zählt dafür
  nicht, ihn wählt der Absender.
- Der Watcher veröffentlicht das Receipt bereits bei Sichtung der Transaktion
  im Mempool (0 Bestätigungen).
- Wird die Transaktion innerhalb von 60 Minuten nach dem Receipt nicht in
  einen Block aufgenommen, veröffentlicht der Watcher ein kind 5 (Deletion)
  auf das Receipt. Clients müssen kind 5 des Watcher-Pubkeys auf Receipts
  respektieren: Sie fragen kind 5 mit `#e` der geladenen Receipt-IDs ab und
  zählen ein zurückgenommenes Receipt weder in Anzahl noch Summe. Nur ein
  kind 5 vom Autor des Receipts (dem Watcher) zählt.

## Watcher-Status – kind 10038 (replaceable)

Autor: Watcher in Pubkey-Form. Er veröffentlicht den Status beim Start und
danach alle 5 Minuten auf seinen Relays.

```json
{
  "kind": 10038,
  "content": "",
  "tags": [
    ["p", "<empfänger-pubkey>"],
    ["address", "4..."],
    ["network", "mainnet"],
    ["height", "3512345"],
    ["version", "0.4.0"],
    ["protocol", "0.5"]
  ]
}
```

- `version` ist die Build-Version des Watchers, `protocol` (ab 0.5) die
  Version dieses Protokolls, die er umsetzt. Fehlt `protocol`, ist der
  Watcher älter als 0.5.
- Ein Client wertet den Watcher als online, wenn der neueste Status höchstens
  15 Minuten alt ist (`created_at`).
- Clients prüfen vor dem Veröffentlichen von kind 10037: `p` ist der eigene
  Pubkey, `network` passt, Status frisch. Die Adresse für kind 10037 kommt aus
  dem Status.
- Watcher in URL-Form veröffentlichen keinen Status.

## Validierung durch Clients

1. Payment-Info des Empfängers (`p`) laden.
2. Ein Receipt ist gültig genau dann, wenn `receipt.pubkey` gleich dem
   Watcher-Pubkey aus der Payment-Info ist und das `p`-Tag zum Empfänger
   passt.
3. Ungültige Receipts werden nicht angezeigt. Intents ohne Receipt zählen
   nicht.
4. Ab 0.5.1: Ein Mainnet-Client behandelt eine Payment-Info mit `network`
   ungleich `mainnet` als deaktiviert (kein Tip, keine Anzeige von Receipts):
   Testnet-Münzen sind wertlos und sollen nicht als echte Tips erscheinen.
5. Eine Summe über alle Receipts sättigt beim grössten darstellbaren Wert,
   statt überzulaufen: Der Watcher des Empfängers kann jeden Betrag
   signieren.

## Watcher-HTTP-API

Basis: `https://<watcher>/v1/`. Authentifizierte Endpunkte verwenden NIP-98.

| Methode | Pfad | Auth | Body / Antwort |
|---|---|---|---|
| GET | `/v1/info` | – | `{"pubkey": hex, "relays": [url…], "network": "mainnet"\|"stagenet", "height": int, "version": string, "protocol": string}` |
| POST | `/v1/accounts` | NIP-98 | Body `{"address": string, "view_key": hex}`. Antwort `{"watcher_pubkey": hex}`. Ein Account pro Pubkey; erneuter POST ersetzt. |
| DELETE | `/v1/accounts` | NIP-98 | Entfernt den Account des aufrufenden Pubkeys. Antwort 204. |

`version` ist die Build-Version, `protocol` (ab 0.5, z. B. `"0.5"`) die
Protokollversion des Watchers. Fehlt `protocol`, ist der Watcher älter als
0.5. Ein Client bietet "Beträge verbergen" nur an, wenn `protocol` ≥ 0.5
(Major.Minor-Vergleich).

Der Watcher prüft bei `POST /v1/accounts`, dass `view_key · G` dem
öffentlichen View-Key der Adresse entspricht, und lehnt Subadressen und
Integrated Addresses ab (400).

Ab 0.5.1: Ein NIP-98-Header gilt einmal; derselbe Event noch einmal innerhalb
des Zeitfensters ergibt 401. Registrierungen sind pro Client-Adresse begrenzt
(IPv6 pro /64) und zusätzlich insgesamt pro Stunde (Betreiber-Einstellung,
Standard 100); darüber antwortet der Watcher 429. Teilen sich mehrere Pubkeys
dieselbe Adresse, bleibt sie beobachtet, solange einer sie noch registriert
hat; eine `pid` ist pro Adresse eindeutig.

## Privacy

Der Watcher-Betreiber sieht alle eingehenden Transaktionen einer
registrierten Wallet, nicht nur Tips. Empfängern wird eine eigene Wallet nur
für Tips empfohlen.

Öffentlich sichtbar sind bei jedem Tip Empfänger, Betrag, Zeitpunkt, die
Ziel-Note (falls vorhanden) und – außer bei `anon` – der Sender. Mit
`["amounts", "hidden"]` (ab 0.5) bleibt der Betrag aus Intent und Receipt
weg: Öffentlich sichtbar sind dann Empfänger, Zeitpunkt, Ziel-Note und –
außer bei `anon` – der Sender, aber kein Betrag. Grenzen: Der Watcher sieht
den Betrag weiterhin; Clients vor 0.18 schreiben ihn weiter in den Intent;
Receipts, die vor der Umstellung veröffentlicht wurden, behalten ihren
Betrag. Clients vor 0.18 verwerfen Receipts ohne `amount`;
Tips an Empfänger mit verborgenen Beträgen, gesendet von 0.18-Clients,
erscheinen in älteren Apps deshalb gar nicht. Ein anonymer
Tip verbirgt den Sender vor Öffentlichkeit, Empfänger und Watcher. Ein Relay
sieht weiterhin die IP-Adresse der Verbindung, über die der Intent kommt;
dagegen hilft nur ein VPN oder Tor beim Sender. Clients senden einen anonymen
Intent über eine eigene Verbindung ohne NIP-42-Anmeldung.

Auch die Frage nach dem Receipt kann den Sender verraten: Wer über seine
angemeldete Verbindung nach den Receipts an einen Empfänger fragt, kurz
nachdem dort ein anonymer Profil-Tip angekündigt wurde, gibt sich dem Relay
zu erkennen. Clients fragen das Receipt eines anonymen Profil-Tips deshalb
ebenfalls über eine eigene Verbindung ohne Anmeldung ab, nur bei den Relays
des Watchers. Receipts zu Notes fallen in der allgemeinen Abfrage für alle
angezeigten Notes nicht auf. Ab 0.5.1 holt ein Client auch die Payment-Info
des Empfängers für einen anonymen Tip über eine eigene Verbindung ohne
Anmeldung (unter Tor über einen eigenen Circuit) und fragt dann nicht nach
den Outbox-Relays des Empfängers: Sonst sähe ein Relay, wer kurz vor dem
anonymen Intent nach genau diesem Empfänger gefragt hat. Ohne Tor oder VPN
bleibt die IP-Adresse dieselbe.

Ein persönlicher Watcher (Pubkey-Form) hält den View-Key beim Empfänger. Er
scannt über öffentliche Nodes: Die Node sieht, dass Blöcke geladen werden,
nicht welche Adresse beobachtet wird. Sie kann Transaktionen verschweigen
(fehlende Receipts) oder unbestätigte erfinden; deshalb muss eine zweite
View-only-Wallet, die über eine andere Node synchronisiert, jede Zahlung mit
gleichem Betrag bestätigen. Ausgeben kann keine Node etwas. Der Status zeigt,
wann der Rechner des Watchers läuft. Relays sehen die IP des Watchers, die
über kind 10037 dauerhaft mit dem Empfänger verbunden ist; dagegen hilft Tor.

## Änderungen

### 0.5.1 (2026-11-17)

Keine neuen Kinds, Tags oder Felder; Watcher und Clients von 0.5 bleiben
kompatibel. Festgeschrieben werden Regeln, die Missbrauch erschweren:

- Watcher: Intents leben höchstens 48 Stunden; höchstens 20 offene Intents
  pro Empfänger und Sender.
- Watcher: nur Zahlungen ab der beim Speichern des Intents gesehenen
  Blockhöhe werden quittiert.
- Watcher: Receipts nur an öffentliche `wss://`-Relays des Empfängers.
- Watcher-API: NIP-98 ohne Wiederholung; Registrierungen pro /64 und
  insgesamt begrenzt; geteilte Adressen bleiben aktiv.
- Clients: Intent zuerst an die Relays des Watchers; höchstens 4 Relays aus
  `GET /v1/info`.
- Clients: kind 5 des Watchers auf Receipts aktiv abfragen und anwenden.
- Clients: Payment-Info für anonyme Tips über eine eigene Verbindung.
- Clients: Testnet-Payment-Info gilt in Mainnet-Clients als deaktiviert;
  Summen sättigen.

### 0.6 (2026-11-17)

- Neues Intent-Tag `pid_nonce`; `pid` ist an den Signaturschlüssel gebunden
  (Regel beim Intent). Ein gebundener Intent ersetzt einen noch offenen,
  unbezahlten ungebundenen mit derselben `pid`.
- Abwärtskompatibel: ungebundene Intents bleiben gültig; Watcher vor 0.6
  ignorieren das Tag.
- `protocol` in `/v1/info` und im Watcher-Status: `"0.6"`.

Prüfwerte (`nonce = 000102030405060708090a0b0c0d0e0f`):

| pubkey | pid |
|---|---|
| `79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798` | `bc14ada49f3e78d6` |
| `c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5` | `4b827455c05329d0` |
