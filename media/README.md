# media.monostr.com

Bild-Hoster für Monostr: ein Blossom-Server (Go) mit NSFW-Prüfung (Python-Container `nsfw`)
hinter Caddy auf einem eigenen Hetzner-VPS (media.monostr.com). Die Bilder
liegen im Bucket `monostr-media` (Hetzner Object Storage, hel1, privat), der Server reicht sie
durch. Design: `docs/superpowers/specs/2026-10-02-plan-10e-media-upload-design.md`.

## Was der Server tut

| Methode und Pfad | Auth | Zweck |
|---|---|---|
| `GET`, `HEAD /<sha256>[.ext]` | nein | Bild ausliefern |
| `PUT /upload` | kind 24242, `t=upload` | Bild hochladen (roh, kein Formular) |
| `HEAD /upload` | kind 24242, `t=upload` | Vorabprüfung ohne Daten |
| `DELETE /<sha256>` | kind 24242, `t=delete` | eigenen Besitz entfernen |
| `GET /` | nein | Regeln, Limits, Meldeadresse |
| `GET /healthz` | nein | prüft Datenbank, Bucket und `nsfw` |

Angenommen werden nur JPEG, PNG, WebP und GIF. Jede Ablehnung trägt `X-Reason` (Text) und
`X-Monostr-Reason` (festes Kürzel: `auth`, `banned`, `banned-content`, `nsfw`, `hash-mismatch`,
`length`, `too-large`, `quota`, `type`, `rate`, `unavailable`, `not-found`, `bad-request`, `internal`).

Der Server speichert keine IP-Adressen: das Limit pro Adresse zählt nur im Arbeitsspeicher,
das Log nennt Methode, Pfad, Status und Dauer, Caddy schreibt weder ein Zugriffsprotokoll noch das
Fehlerprotokoll der Handler (dort stünde bei einem Ausfall die Adresse des Clients). Das Compose-Netz
hat IPv6, damit Caddy auch bei IPv6-Clients die echte Adresse sieht und nicht alle unter einer zählen.

## Erstinstallation (einmalig, als root auf dem Host)

1. `apt-get install -y docker.io docker-compose-v2 docker-buildx`; `/etc/docker/daemon.json` mit
   `{"log-driver":"json-file","log-opts":{"max-size":"50m","max-file":"5"}}`, dann `systemctl restart docker`.
2. Swap 2 GB: `fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`,
   `/swapfile none swap sw 0 0` in `/etc/fstab`, `vm.swappiness=10` in `/etc/sysctl.d/90-monostr.conf`.
3. Firewall: `ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable`.
4. `/opt/monostr/media/.env` (Modus 600) mit `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`,
   `S3_SECRET_KEY`. Die Datei liegt nur auf dem Host; `deploy.sh` überträgt und löscht sie nie.
5. Lokal `./deploy.sh check`, dann `./deploy.sh up`. Der erste Bau lädt das Modell (22 MB) und die
   Python-Pakete; Caddy holt das Zertifikat, sobald `media.monostr.com` auf den Host zeigt.
6. Cron: `cp ops/media.cron /etc/cron.d/monostr-media`, `mkdir -p /var/backups/monostr-media && chmod 700 /var/backups/monostr-media`.

## Updates

`./deploy.sh up` (rsync, `docker compose build`, `up -d`, Neustart von Caddy). Die Version in
`GET /healthz` ist `git describe` zum Zeitpunkt des Deploys: vor einem Release erst taggen.
Image-Digest von Caddy im `compose.yml` ändern, dann deployen. Kein Watchtower.

## Konfiguration

Alles in `compose.yml` unter `media.environment`, außer dem S3-Zugang (`.env`):

| Variable | Standard | Bedeutung |
|---|---|---|
| `MEDIA_MAX_BYTES` | 10485760 | größte Datei |
| `MEDIA_PUBKEY_UPLOADS_PER_DAY` | 50 | Uploads pro Pubkey und UTC-Tag (gespeicherte und als NSFW abgelehnte) |
| `MEDIA_PUBKEY_QUOTA_BYTES` | 524288000 | Kontingent pro Pubkey |
| `MEDIA_IP_UPLOADS_PER_DAY` | 100 | Uploads pro Adresse und UTC-Tag (IPv6 pro /64), nur im Arbeitsspeicher |
| `MEDIA_GLOBAL_BYTES_PER_DAY` | 5368709120 | neu gespeicherte Bytes pro UTC-Tag insgesamt (Kostenbremse) |
| `MEDIA_MAX_UPLOADS_IN_FLIGHT` | 16 | Uploads, die gleichzeitig bearbeitet werden (jeder hält sein Bild im Speicher); darüber `503` mit `Retry-After`. Pro Adresse höchstens 4 zugleich |
| `MEDIA_NSFW_THRESHOLD` | 0.60 | ab diesem Wert wird ein Bild abgelehnt |
| `MEDIA_CONTACT_NPUB` | Projekt-npub | Meldeadresse auf der Startseite |

Nach einer Änderung `./deploy.sh up`.

## Betrieb

- Prüfen: `curl -s https://media.monostr.com/healthz`, `docker compose ps`, `docker compose logs --tail 50 media`.
- Admin-Befehle (auf dem Host, in `/opt/monostr/media`): `docker compose exec media /monostr-media admin <Befehl>`
  - `delete <sha256>` – Bild und alle Besitzer entfernen.
  - `ban-hash <sha256> [Notiz]` – entfernen und dauerhaft sperren. `unban-hash <sha256>`.
  - `ban-pubkey <npub|hex> [--purge] [Notiz]` – keine Uploads mehr; `--purge` entfernt seine Bilder,
    soweit kein anderer sie besitzt. `unban-pubkey <npub|hex>`.
  - `log <npub|hex> [n]` – die letzten Uploads und Ablehnungen eines Pubkeys.
  - `stats` – Anzahl, Belegung, Zahlen des Tages, größte Uploader.
  - `near <wert> [n]` – angenommene Bilder mit einem NSFW-Wert zwischen `<wert>` und der Schwelle.
  - `gc` – Objekte im Bucket ohne Datenbankzeile entfernen (älter als eine Stunde; läuft sonntags per Cron).
- Eine Meldung bearbeiten: Adresse des Bildes ansehen, `ban-hash <sha256> <Notiz>`; bei Wiederholung
  `log <npub>` und `ban-pubkey <npub> --purge`.
- Schwelle prüfen: nach den ersten Wochen `near 0.40` ansehen. Stehen dort nur harmlose Bilder (Diagramme,
  Comics, einfarbige Flächen), passt die Schwelle; stehen dort bedenkliche, Schwelle senken.

## NSFW-Prüfung

Container `nsfw` (`nsfw/server.py`): `POST /classify` mit den Bild-Bytes antwortet
`{"score": 0..1, "frames": n}`. Modell: Marqo `nsfw-image-detection-384` (Apache-2.0) als ONNX-Export
`ICIJ/nsfw-image-detection-384-onnx`, Revision und SHA-256 in `nsfw/fetch_model.py` bzw. `nsfw/server.py`
festgenagelt. Bei animierten GIF und WebP zählt der höchste Wert von bis zu acht verteilten Einzelbildern.
Ein Bild mit Transparenz wird auf weißem und auf schwarzem Grund bewertet. Der Container bewertet ein Bild zur Zeit;
eine Animation, deren Einzelbilder mal Fläche 250 Millionen Pixel übersteigen, lehnt schon der Server ab (`413 too-large`),
der Container ein zweites Mal. Antwortet der Container nicht, nimmt der Server nichts an (503); ein fehlgeschlagener
Prüfversuch zählt für die Adresse, nicht für den Pubkey.

Die Schwelle und wie sie gemessen wurde: `nsfw/CALIBRATION.md`. Neu messen:

```
cd media/nsfw
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python fetch_model.py marqo-nsfw-384.onnx
.venv/bin/python fetch_calibration.py calib
NSFW_MODEL=marqo-nsfw-384.onnx .venv/bin/python calibrate.py calib scores.csv
```

Tests des Containers: `NSFW_MODEL=marqo-nsfw-384.onnx .venv/bin/python -m unittest -v test_server test_hardening`.

## Backups

- `ops/media.cron`: 03:30 Online-Backup von `media.db` nach `/var/backups/monostr-media` (7 Generationen),
  03:45 Kopie auf den Relay-VPS, sonntags 04:15 `admin gc`.
- Auf dem Relay-VPS (relay.monostr.com) nimmt der Systemuser `mediabackup` die Kopie entgegen: Home `/srv/backup-media`,
  Ziel `/srv/backup-media/data`, `~/.ssh/authorized_keys` mit
  `command="/usr/bin/rrsync -wo /srv/backup-media/data",restrict <media-key>` – der Media-Host kann nur dorthin
  schreiben, nichts lesen. Der Schlüssel liegt auf dem Media-Host in `/root/.ssh/monostr-backup`.
- Der Bucket selbst hat kein Backup: geht er verloren, sind alle Bilder weg (für die Beta hingenommen,
  Spec Abschnitt 7). Die S3-Schlüssel werden nicht mitkopiert; sie lassen sich in der Hetzner-Console neu erzeugen.

## Tests

`go test ./...` in `media/`. Zwei Tests laufen nur mit einer Gegenstelle:

- `NSFW_URL=http://127.0.0.1:8081 go test ./internal/classify/ -run Running -v` gegen einen laufenden Container `nsfw`.
- `S3_TEST_ENDPOINT=… S3_TEST_REGION=… S3_TEST_BUCKET=… S3_TEST_ACCESS_KEY=… S3_TEST_SECRET_KEY=… go test ./internal/storage/ -run RealBucket -v`
  gegen einen echten Bucket (legt ein Objekt `selftest-…` an und löscht es wieder).
