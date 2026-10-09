# Monostr Tips – Monero-Zahlungen für Nostr-Notes und -Profile

Status: Draft
Version: 0.2

## Zusammenfassung

Dieses Dokument beschreibt drei Event-Kinds, mit denen ein Nutzer eine
Nostr-Note oder ein Profil mit einer Monero-Zahlung ("Tip") bedenken kann, und einen
HTTP-Dienst ("Watcher"), der eingehende Zahlungen für registrierte Empfänger
erkennt und quittiert.

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
    ["network", "mainnet"]
  ]
}
```

- `address`: Monero-Standardadresse (Mainnet `4…`, Stagenet `5…`). Keine
  Subadresse, keine Integrated Address.
- `watcher`: URL des Watchers und dessen Nostr-Pubkey (hex).
- `network`: `mainnet` oder `stagenet`.
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
    ["type", "tip"],
    ["expiration", "<unix-timestamp>"]
  ]
}
```

- `e` ist optional. Mit `e` gilt der Tip einer Note, ohne `e` dem Profil des
  Empfängers aus `p`. Ein vorhandenes, aber ungültiges `e` (nicht 64
  Hex-Zeichen klein) macht den Intent ungültig; er gilt nicht als Profil-Tip.
- `pid`: 8 Byte kryptografisch zufällig, 16 Hex-Zeichen. Wird zur Payment-ID
  der Integrated Address.
- `type`: `tip` (eigenständiger Tip, Standard in Monostr), `like` oder
  `boost` (Tip, den der Client an einen Like bzw. Repost koppelt). Watcher
  kopieren den Wert unverändert in das Receipt.
- `expiration` (NIP-40): empfohlen `created_at + 86400`.
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
Watchers veröffentlicht (siehe `GET /v1/info`).

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
- `amount`: tatsächlich empfangener Betrag.
- Kein txid.
- Mehrere Zahlungen mit derselben `pid` erzeugen je ein Receipt mit
  derselben `intent`-Referenz.
- Der Watcher veröffentlicht auf seinen Relays und den Write-Relays des
  Empfängers (NIP-65).
- Der Watcher veröffentlicht das Receipt bereits bei Sichtung der Transaktion
  im Mempool (0 Bestätigungen).
- Wird die Transaktion innerhalb von 60 Minuten nach dem Receipt nicht in
  einen Block aufgenommen, veröffentlicht der Watcher ein kind 5 (Deletion)
  auf das Receipt. Clients müssen kind 5 des Watcher-Pubkeys auf Receipts
  respektieren.

## Validierung durch Clients

1. Payment-Info des Empfängers (`p`) laden.
2. Ein Receipt ist gültig genau dann, wenn `receipt.pubkey` gleich dem
   Watcher-Pubkey aus der Payment-Info ist und das `p`-Tag zum Empfänger
   passt.
3. Ungültige Receipts werden nicht angezeigt. Intents ohne Receipt zählen
   nicht.

## Watcher-HTTP-API

Basis: `https://<watcher>/v1/`. Authentifizierte Endpunkte verwenden NIP-98.

| Methode | Pfad | Auth | Body / Antwort |
|---|---|---|---|
| GET | `/v1/info` | – | `{"pubkey": hex, "relays": [url…], "network": "mainnet"\|"stagenet", "height": int, "version": string}` |
| POST | `/v1/accounts` | NIP-98 | Body `{"address": string, "view_key": hex}`. Antwort `{"watcher_pubkey": hex}`. Ein Account pro Pubkey; erneuter POST ersetzt. |
| DELETE | `/v1/accounts` | NIP-98 | Entfernt den Account des aufrufenden Pubkeys. Antwort 204. |

Der Watcher prüft bei `POST /v1/accounts`, dass `view_key · G` dem
öffentlichen View-Key der Adresse entspricht, und lehnt Subadressen und
Integrated Addresses ab (400).

## Privacy

Der Watcher-Betreiber sieht alle eingehenden Transaktionen einer
registrierten Wallet, nicht nur Tips. Empfängern wird eine eigene Wallet nur
für Tips empfohlen.

Öffentlich sichtbar sind bei jedem Tip Empfänger, Betrag, Zeitpunkt, die
Ziel-Note (falls vorhanden) und – außer bei `anon` – der Sender. Ein anonymer
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
angezeigten Notes nicht auf. Was bleibt: Ein Relay sieht, wer kurz vor einem
anonymen Tip die Payment-Info des Empfängers abgefragt hat; das ist ein
Hinweis, kein Beleg – dieselbe Abfrage entsteht bei jedem geöffneten
Tip-Dialog.
