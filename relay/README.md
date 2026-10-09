# relay.monostr.com

Öffentliches Nostr-Relay (strfry) hinter Caddy auf einem eigenen Hetzner-VPS
(relay.monostr.com). Design: `docs/superpowers/specs/2026-09-28-plan-8b-relay-move-design.md`;
Relay-Policy (offen, Größen-/Filter-Limits, Retention) aus
`docs/superpowers/specs/2026-09-25-deployment-design.md` gilt unverändert.

`strfry.conf` ist die einzige Quelle für beide Relays: `../watcher/deploy.sh` erzeugt daraus
die Stagenet-Kopie (nur `info.name` anders).

## Erstinstallation (einmalig, als root auf dem Host)

1. `apt-get install -y docker.io docker-compose-v2`; `/etc/docker/daemon.json` mit
   `{"log-driver":"json-file","log-opts":{"max-size":"50m","max-file":"5"}}`, dann `systemctl restart docker`.
2. Swap 2 GB: `fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`,
   `/swapfile none swap sw 0 0` in `/etc/fstab`, `vm.swappiness=10` in `/etc/sysctl.d/90-monostr.conf`.
3. Firewall: `ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable`.
4. `mkdir -p /opt/monostr/relay && chmod 750 /opt/monostr`; lokal `./deploy.sh check`, dann `./deploy.sh up`.
   Caddy holt das Zertifikat erst, wenn `relay.monostr.com` per DNS auf den Host zeigt
   (bis dahin Retries im Log, harmlos).
5. Cron: `cp ops/relay.cron /etc/cron.d/monostr-relay` (Retention 04:00, Log `/var/log/monostr-retention.log`).

## Updates

`./deploy.sh up` (rsync + `docker compose up -d`, danach `docker compose restart relay`).
Image-Digests im `compose.yml` ändern, dann deployen. Kein Watchtower.

`strfry.conf` ist ein Single-File-Bind-Mount; `docker compose up -d` erkennt reine
Inhaltsänderungen der Datei nicht (kein Service-Diff) und rsync ersetzt die Datei über eine
neue Inode, sodass der laufende Container sonst die alte Config weiterbedient. Deshalb
restartet `deploy.sh up` den `relay`-Service nach jedem Deploy (~2s Downtime); ohne diesen
Restart wirken Config-Änderungen erst beim nächsten manuellen Neustart.

## Betrieb

- Prüfen: `curl -H 'Accept: application/nostr+json' https://relay.monostr.com` (NIP-11),
  `nak req -k 1 --limit 1 wss://relay.monostr.com`, `docker compose logs --tail 50 relay`.
- Event-Zahl: `docker compose exec -T relay /app/strfry export 2>/dev/null | wc -l`.
- Export/Import (Umzug, Backup): `strfry export > events.jsonl`, `strfry import < events.jsonl`
  (idempotent, Duplikate werden übersprungen; `--since <unix>` für Deltas).
- Retention (`ops/relay.cron`): Cron 04:00 löscht Inhalte älter als ein Jahr
  (`/app/strfry delete --age 31536000 --filter '{"kinds":[1,6,7,4,9735,30023]}'`), Cron 04:15 Gift-Wraps
  (kind 1059, NIP-17-DMs) älter als 30 Tage (`--age 2592000 --filter '{"kinds":[1059]}'`; Clients halten
  ihre Nachrichten lokal, das Relay ist nur Briefkasten; NIP-59 datiert `created_at` bis zu zwei Tage zurück,
  ein Wrap liegt also 28–30 Tage). `--dry-run` zeigt vorher, was gelöscht würde. Profile, Kontaktlisten,
  Relay-Listen, Payment-Info, Intents und Receipts bleiben unbegrenzt. strfry nimmt Events an, die bis zu
  drei Jahre alt sind (`rejectEventsOlderThanSeconds`).
- Deploy der Retention (Plan 10d, durch den Nutzer): `scp relay/ops/relay.cron root@relay.monostr.com:/etc/cron.d/monostr-relay`,
  vorher auf dem Host `cd /opt/monostr/relay && docker compose exec -T relay /app/strfry delete --dry-run --age 2592000 --filter '{"kinds":[1059]}'`.
- Der Host ist auch der geplante Platz für den strfry-Router (Firehose zum Such-Relay).
- NIP-42: Kinds 4/1059 nur nach AUTH und nur für Beteiligte lesbar (`nak req --auth --sec <key> -k 1059 wss://relay.monostr.com`).

## Backups

- Der VPS hat Hetzner-Backups (Console, 7 tägliche Snapshots); Relay-Daten liegen im Volume `relay_strfry-data`.
- Der Host nimmt nachts die Watcher-Backups von node.xmr.rocks entgegen: Systemuser `nodebackup`
  (Home `/srv/backup`, Shell `/bin/sh`), `~/.ssh/authorized_keys` mit
  `command="/usr/bin/rrsync -wo /srv/backup/node",restrict <node-key>` — der Node kann nur nach
  `/srv/backup/node` schreiben, nichts lesen. Inhalt: `watcher-YYYY-MM-DD.db` (7 Generationen) und
  `env-mainnet` (Watcher-Secrets, 0600). Sender-Cron: `watcher/ops/monostr.cron`.
