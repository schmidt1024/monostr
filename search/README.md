# search.monostr.com

NIP-50-Such-Relay (searchnos) hinter Caddy auf einem eigenen Hetzner-VPS
(search.monostr.com). Design:
`docs/superpowers/specs/2026-09-28-search-relay-design.md`.

searchnos zieht Events selbst von `SRC_RELAYS` (kind 0, 1, 30023) und lehnt fremde
`EVENT`-Writes ab. Das Image wird auf dem Host aus dem gepinnten Commit gebaut
(`deploy.sh`, Variable `SEARCHNOS_COMMIT`; Quelle in `/opt/monostr/searchnos-src`).

## Erstinstallation (einmalig, als root auf dem Host)

1. `apt-get install -y docker.io docker-compose-v2 git`; `/etc/docker/daemon.json` mit
   `{"log-driver":"json-file","log-opts":{"max-size":"50m","max-file":"5"}}`, `systemctl restart docker`.
2. Swap 4 GB (Rust-Build): `fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`,
   `/swapfile none swap sw 0 0` in `/etc/fstab`, `vm.swappiness=10` in `/etc/sysctl.d/90-monostr.conf`.
3. Firewall: `ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable`.
4. `mkdir -p /opt/monostr/search && chmod 750 /opt/monostr`; lokal `./deploy.sh check`, dann `./deploy.sh up`
   (erster Build ca. 15–30 min auf 2 vCPU). Caddy holt das Zertifikat, sobald DNS auf den Host zeigt.
5. Cron: `cp ops/search.cron /etc/cron.d/monostr-search` (Retention 04:10, Negentropy sonntags 05:00).

## Updates

Neuen Commit in `deploy.sh` (`SEARCHNOS_COMMIT`) eintragen, `./deploy.sh up`. Caddy-Digest im `compose.yml`.

## Betrieb

- Prüfen: `curl -H 'Accept: application/nostr+json' https://search.monostr.com` (NIP-11, NIP 50),
  `curl --fail https://search.monostr.com/healthz` (503, wenn das neueste Event älter als 300 s ist),
  `nak req -k 1 --search monero -l 5 wss://search.monostr.com`, `docker compose logs --tail 50 searchnos`.
- Daten: Volume `search_searchnos-data` (`hot.events`, `partitions/<unix-day>.events|.search`, `visibility/`).
  Statistik: `docker compose run --rm --no-deps searchnos /app/searchnos --db-path /data stat`
  (nur bei gestopptem `searchnos`: exklusiver Lock).
- Retention: vorerst AUS (Cron-Zeile auskommentiert). Partitionen sind nach `created_at` geschnitten; Löschen alter
  Partitionen würde auch lange unveränderte Profile (kind 0) entfernen. `ops/retention.sh N` liegt bereit; einschalten
  (oder vorher Kinds trennen), wenn das Volume Richtung 20 GB geht. `SEARCH_DAYS` aus demselben Grund nicht gesetzt.
- Negentropy: `docker compose kill -s SIGUSR2 searchnos` gleicht die letzten `NEGENTROPY_DAYS` Tage ab.
- Kein NIP-11-Icon (searchnos kennt nur Name/Beschreibung).
