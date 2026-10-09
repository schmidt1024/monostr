# monostr-watcher

Go-Dienst, der Tip-Intents (kind 9738) beobachtet, über monero-lws auf die
Zahlung wartet und Tip-Receipts (kind 9739) veröffentlicht. Protokoll:
`docs/protocol/monostr-tips.md`.

## Bauen und testen
```bash
export PATH=$HOME/.local/go/bin:$PATH   # Go 1.27
cd watcher
go test ./...
CGO_ENABLED=0 go build ./cmd/monostr-watcher
```

## Konfiguration

Alle Einstellungen kommen aus Umgebungsvariablen (siehe `.env.example`).
Pflicht: `WATCHER_SECRET_KEY`, `WATCHER_NETWORK`, `WATCHER_RELAYS`,
`WATCHER_PUBLIC_URL`. Optional: `WATCHER_PUBLIC_RELAYS` (Relays, die `/v1/info`
meldet, falls sie von `WATCHER_RELAYS` abweichen). `LWS_ADMIN_KEY` ist Pflicht, sobald monero-lws ohne
`--disable-admin-auth` läuft (Standard).

## Deployment (node.xmr.rocks)

Watcher, monero-lws und Caddy laufen in Docker Compose auf dem Node-Host. Design:
`docs/superpowers/specs/2026-09-25-deployment-design.md`. Das öffentliche Relay
`wss://relay.monostr.com` läuft seit Plan 8b auf einem eigenen VPS (`../relay/`,
Spec `docs/superpowers/specs/2026-09-28-plan-8b-relay-move-design.md`); der Watcher
erreicht es über `wss://`. Nur das Stagenet-Relay läuft noch hier.

### Erstinstallation (einmalig, als root auf dem Host)

1. Swap und Firewall: `fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`,
   Zeile `/swapfile none swap sw 0 0` in `/etc/fstab`, `sysctl -w vm.swappiness=10`
   (+ `/etc/sysctl.d/90-monostr.conf`), `ufw allow 80/tcp && ufw allow 443/tcp`.
2. Netze: `docker network create monero && docker network create stagenet`.
3. monerod mit ZMQ neu anlegen (Ports **nicht** published):
   ```
   docker stop monerod && docker rm monerod
   docker run -d --name monerod --restart unless-stopped --network monero \
     -p 18080:18080 -p 18089:18089 -v bitmonero:/home/monero \
     ghcr.io/sethforprivacy/simple-monerod:latest \
     --rpc-restricted-bind-ip=0.0.0.0 --rpc-restricted-bind-port=18089 --no-igd \
     --zmq-rpc-bind-ip=0.0.0.0 --zmq-rpc-bind-port=18082 --confirm-zmq-rpc-external-bind \
     --zmq-pub=tcp://0.0.0.0:18083
   ```
   Container, die per Legacy-`--link monerod` angelegt waren (auf dem Host: `tor`
   für den Onion-Dienst), starten danach nicht mehr. `tor` wurde deshalb ohne Link
   im Netz `monero` neu angelegt, mit expliziter torrc (`/opt/monostr/tor/torrc`,
   `HiddenServicePort 18080/18089 → monerod`), `--user 104:107`, tmpfs als
   `DataDirectory` und dem bestehenden Volume `tor-keys` (Onion-Adresse bleibt).
4. `mkdir -p /opt/monostr/watcher && chmod 750 /opt/monostr`; lokal `./deploy.sh check`, dann `./deploy.sh sync` (schiebt die Dateien ohne Start).
5. Auf dem Host `cp .env.example .env`, `WATCHER_SECRET_KEY=$(openssl rand -hex 32)` eintragen,
   `chmod 600 .env`. Dann `docker compose up -d monero-lws`,
   `docker compose exec monero-lws monero-lws-admin create_admin` → Feld `key` der JSON-Antwort als `LWS_ADMIN_KEY` in `.env` (nicht die Adresse).
6. Lokal `./deploy.sh mainnet`. Prüfen: `curl https://watcher.monostr.com/v1/info`.
7. Cron installieren: `cp ops/monostr.cron /etc/cron.d/monostr` (Backup 03:30);
   `mkdir -p /var/backups/monostr && chmod 700 /var/backups/monostr`. Docker-Log-Defaults:
   `/etc/docker/daemon.json` mit `{"log-driver":"json-file","log-opts":{"max-size":"50m","max-file":"5"}}`
   (gilt für neu angelegte Container; Compose-Services setzen es zusätzlich selbst).

Die Images sind per Digest gepinnt (LWS **v1.0.1**: 1.0.2/`latest` stirbt auf dem Host mit
SIGILL). Update: neuen Digest auf dem Host mit `docker run --rm --entrypoint monero-lws-daemon
<image> --version` testen, dann in beiden Compose-Files ändern und deployen.

### Updates

`./deploy.sh mainnet` (baut das Image `monostr-watcher:current` mit der Git-Version als Build-Arg
und startet geänderte Services). Ein manuelles `docker compose up -d` startet das zuletzt gebaute Image.
Für LWS/Caddy den Digest im Compose-File ändern, dann deployen (Relay: siehe `../relay/README.md`).
Watchtower aktualisiert weiterhin nur `monerod` und `tor`.

### Stagenet (E2E-Checkliste)

Gleicher Host, eigenes Netz `stagenet`, eigener pruned Stagenet-monerod
(erster Sync: Stunden). Der Mainnet-Caddy liefert
`https://stagenet.watcher.monostr.com` (Watcher) und
`wss://stagenet.watcher.monostr.com/relay` (strfry) aus.

1. Auf dem Host `cp .env.stagenet.example .env.stagenet`, Secret-Key wie oben, `chmod 600`.
2. Lokal `./deploy.sh stagenet`; dann auf dem Host
   `docker compose -f compose.stagenet.yml --env-file .env.stagenet exec stagenet-lws monero-lws-admin --network stage create_admin`
   → `LWS_ADMIN_KEY` in `.env.stagenet`, erneut `./deploy.sh stagenet`.
3. Sync abwarten: `curl https://stagenet.watcher.monostr.com/v1/info` zeigt `height` nahe
   der Stagenet-Höhe (z. B. von https://stagenet.xmrchain.net).
4. Ablauf: `docs/testing/stagenet-e2e.md`. Danach
   `docker compose -f compose.stagenet.yml --env-file .env.stagenet down` (Volumes bleiben).

## Betrieb

- Logs sind JSON auf stdout. Request-Bodies und View-Keys werden nie
  geloggt.
- Der Sweep läuft jede Minute: Receipts ohne Bestätigung nach 60 min werden
  per kind 5 zurückgezogen, abgelaufene Intents und ihre Webhooks entfernt,
  nicht veröffentlichte Receipts erneut publiziert.
- Alle 5 Minuten werden Intents der letzten Stunde erneut von den Relays
  abgeholt, damit Verbindungsabbrüche keine Tips verlieren.
- Neustart: der Watcher startet zuerst den HTTP-Listener, holt Intents
  aus der Ausfallzeit von den Relays nach, registriert fehlende Webhooks und
  stößt für Adressen mit offenen Intents einen LWS-`rescan` ab der letzten
  bekannten Blockhöhe an. Schlägt der `rescan` fehl, wiederholt ihn der
  Sweep; bis dahin bleibt die gespeicherte Blockhöhe stehen.
- Datenbank: SQLite unter `/data/watcher.db` (Volume `watcher-data`).
  Backup = Datei kopieren, während der Dienst läuft ist WAL aktiv.
- `/data` gehört im Image uid 65532 (distroless `nonroot`), damit der
  Dienst ohne Root-Rechte in ein frisches Volume schreiben kann. Ein
  Bind-Mount statt Named Volume muss ebenfalls uid 65532 gehören
  (`chown 65532:65532 <dir>`).

- Backup (`ops/monostr.cron`): Cron 03:30 sichert `watcher.db` per
  `sqlite3 .backup` (Container `keinos/sqlite3`, als `--user 0:0`, weil das
  Backup-Verzeichnis root-only ist) nach `/var/backups/monostr/`, 7 Generationen. LWS-Daten
  (View-Keys) werden absichtlich nicht kopiert; nach einem Verlust legt der
  Watcher Accounts beim nächsten `POST /v1/accounts` neu an.
  Off-host: Cron 03:45 kopiert `.env` als `env-mainnet` dazu und spiegelt das Verzeichnis per
  rsync auf den Relay-VPS (`nodebackup@relay.monostr.com`, Key `/root/.ssh/monostr-backup`, Gegenseite
  rrsync write-only, Ziel `/srv/backup/node`; Log `/var/log/monostr-backup.log`). Der Relay-VPS
  hat Hetzner-Backups. Einrichtung der Gegenseite: `relay/README.md`, Abschnitt „Backups".
- Protokoll 0.2: Intents ohne `e` sind Tips an ein Profil (`note_id` leer),
  Intents mit `anon`-Tag anonyme Tips (Spalte `intents.anon`; das Receipt
  nennt dann keinen Sender). Die Spalte ergänzt der Watcher beim ersten Start
  auf einer älteren Datenbank selbst. Das Limit „60 Intents pro Sender und
  Stunde“ greift bei Einmal-Schlüsseln nicht; es schützt die Obergrenze von
  500 offenen Intents pro Empfänger.
- Relay-Retention: läuft auf dem Relay-Host (`../relay/ops/relay.cron`), siehe `../relay/README.md`.
  Das Stagenet-Relay hier hat keine Retention.
- LWS-Beobachtungen (Stagenet-E2E 2026-09-25, monero-lws 1.0.1): Hooks kommen
  bei Bestätigung 0 und 1 mit kleingeschriebener `payment_id`; LWS loggt bei
  jedem Webhook-Versand `[PARSE URI] regex not matched` (harmlos, Zustellung
  klappt); `rescan` mit einer Höhe ≥ LWS-Scanhöhe liefert HTTP 500 „Invalid
  blockchain height" (der Watcher wiederholt minütlich; Höhe klemmen ist
  offen). Webhooks überleben einen Watcher-Neustart.
- Speicher: 3,8 GB RAM + 4 GB Swap; Limits je Container in den Compose-Files.
  `docker stats --no-stream` zeigt die Auslastung.

## Privacy

Der Watcher speichert nur, was er braucht: Nostr-Pubkey des Empfängers, Wallet-Adresse und
privaten View-Key (in monero-lws), offene Tip-Intents und Receipts. Client-IP-Adressen dienen
nur dem Rate-Limit im Arbeitsspeicher und werden weder geloggt noch gespeichert; das HTTP-Log
enthält Methode, Pfad, Status und Dauer, kein Body, keine Adresse; Caddy schreibt kein Access-Log.
`DELETE /v1/accounts` (Tips abschalten in der App) löscht das Watcher-Konto; in monero-lws wird das
Konto nur auf `inactive` gesetzt, Adresse und View-Key bleiben dort, bis der Betreiber sie manuell
entfernt (LWS-Admin, kein Purge-Endpunkt in v1.0.1). Dieser Text steht so auch im Onboarding der
App (`setup_watcher_privacy`) – ändert sich Logging oder Löschverhalten, ändert sich beides.

Der Betreiber von monero-lws sieht alle eingehenden Transaktionen einer
registrierten Wallet. Empfängern wird eine eigene Wallet nur für Tips
empfohlen.
