# translate.monostr.com

LibreTranslate (CPU only) behind Caddy on its own ARM64 VPS (`root@2.31.205.186`, Ubuntu 26.04).
Design: `docs/superpowers/specs/2026-11-03-note-translation-design.md`. Endpoint:
`POST https://translate.monostr.com/translate` (JSON, open: no API key).

Languages loaded: en, de, es, fr, it, pt, ru, tr. Image pinned to `libretranslate/libretranslate:v1.9.6`
by multi-arch index digest (see `compose.yml`).

## Privacy policy

- Request bodies are never logged: no `tty`, Caddy `log { output discard }`, container logs rotate (10 MB x 3).
- No IP addresses are logged: Caddy's access log is discarded and its error/upstream loggers (which carry client IPs) are excluded; only startup/TLS messages remain; rate-limit counters (per IP, expiring within a minute) live in redis without persistence (`--save ""`, no AOF).
- No web UI (`LT_DISABLE_WEB_UI`), no file translation, no suggestions, no stored translations.
- No API keys and no database: the only persistent volume is the downloaded models.
- Abuse protection: `LT_REQ_LIMIT=120` texts/min per client IP (a note costs 1, an article batch of N blocks costs N) (keyless requests are bucketed by the
  first `X-Forwarded-For` IP that Caddy sets; counters live in a small redis container because gunicorn runs several
  worker processes and in-memory counters would be per worker), `LT_CHAR_LIMIT=5000`, Caddy 64 KB body cap.

Environment names were checked against `libretranslate/default_values.py` (v1.9.x main): each is `LT_` plus the
upper-case argument name. `LT_SUGGESTIONS=false` exists. `LT_UPDATE_MODELS=true` is added so the first start
downloads the models into the volume (the stock image ships without them). It also checks for model
updates on every start, so the container needs outbound network access at each start.
Caddy `request_body max_size 64KB` caps request size; `LT_CHAR_LIMIT=5000` caps text per request.

## Bootstrap (once)

```
ssh root@2.31.205.186 'bash -s' < ops/bootstrap.sh     # Docker, compose, ufw 22/80/443, unattended-upgrades
```

## Deploy

```
./deploy.sh check     # rsync to /tmp on the host + `docker compose config` -> CHECK_OK
./deploy.sh up        # rsync to /opt/monostr/translate + pull + up -d
```

First start downloads the models (several minutes; watch `docker compose logs -f libretranslate`).
Caddy fetches the certificate once DNS points at the host.

## Smoke and bench

```
ops/smoke.sh [base url]   # fr->de, 40 requests of 5 texts each (10 at a time) must hit a 429, HTML-shield spans survive (~2 min)
ops/bench.sh [base url] [runs]   # 20 runs per case, p50/p95 in ms (~5 min)
```

Bench metric: curl `time_starttransfer - time_pretransfer` per request, i.e. server time until the first
byte after the request is sent. It includes about one network round trip, so it is conservative. Requests are
made unique (LibreTranslate caches identical translations) and paced so the total cost stays <= 100 texts/min (notes 2.4 s apart, 5-block articles 3.6 s apart); any non-200 aborts that case and is not timed.
Do not run smoke and bench at the same time from one IP.
Cached (identical) translations cost only 1 text against the limit, whatever their size.

Gate (user decision 2026-11-03): direct pairs (one of them English) p50 < 1 s, pivot pairs (via English) p50 < 1.5 s;
the server stays on the 2-core CAX11. If the HTML-shield smoke check fails, use numbered markers without HTML in the app.

On 2 cores the smoke's rate-limit check may print a false FAIL: the limiter deducts a request's cost after it is
done, and the server cannot finish 120 texts/min. The limiter itself was proven live (short unique texts, 429s,
redis key present).

## IPv6 clients

The compose network has IPv6 (`enable_ipv6: true`, ULA `fd00:7a:7b::/64`). Without it Docker forwards IPv6
connections to the published ports through its userland proxy with the bridge gateway (`172.18.0.1`) as source,
so all IPv6 users shared one rate-limit bucket (seen 2026-11-03: redis key `LIMITS:LIMITER/172.18.0.1/...` after
an IPv6 request). With an IPv6 network Docker DNATs IPv6 like IPv4 (ip6tables, on by default since Docker 27) and
Caddy, and so `X-Forwarded-For`, sees the client's real address. Only caddy publishes ports; libretranslate and
redis stay unpublished. (Alternative not taken: caddy with `network_mode: host`, which would need libretranslate
published on 127.0.0.1.)

The network's config changes, so the first deploy after this needs the network recreated:

```
./deploy.sh check
ssh root@2.31.205.186 'cd /opt/monostr/translate && docker compose down'   # keeps the volumes
./deploy.sh up
```

Verify after the deploy:

1. `ssh root@2.31.205.186 'docker network inspect translate_default --format "{{.EnableIPv6}} {{json .IPAM.Config}}"'`
   shows `true` and the `fd00:7a:7b::/64` subnet.
2. From another host with IPv6: `curl -6 -s -H 'Content-Type: application/json' -d '{"q":"Bonjour","source":"fr","target":"en","format":"text"}' https://translate.monostr.com/translate`
3. `ssh root@2.31.205.186 'cd /opt/monostr/translate && docker compose exec redis redis-cli --scan --pattern "LIMITS*"'`
   lists a key with that host's IPv6 address, not `172.18.0.1`. (The key expires within a minute: run 3 right after 2.)
4. Same with `curl -4`: the key carries the IPv4 address as before.

## Known limitation

The per-IP limit can be dodged by rotating addresses within an IPv6 /64. Accepted for v1; mitigation later
(nftables hashlimit per /64 or a Caddy rate-limit plugin).

Healthcheck: queries `/languages` on port 5000, so the container is unhealthy until the models are loaded
(`start_period` 600 s covers the first model download).

## Bench results

Interim measurement by the controller (2-core CAX11, unique ~200-char notes, server time):

| pair | p50 | p90 |
|---|---|---|
| fr->en | 635 ms | 712 ms |
| fr->de (pivot via English) | 1295 ms | 1367 ms |

(This interim run reported p90; `ops/bench.sh` prints p50/p95: paste its output here after the next run.)
Gate passed by user decision: direct pairs < 1 s, pivot pairs < 1.5 s, stays on CAX11 (2 cores / 4 GB).
The app keeps article requests small for this (first request title + first block, then <= 8 texts / 1500 chars).

## Operation

- Logs: `docker compose logs --tail 50 libretranslate`
- Update: change the tag and digest in `compose.yml`, `./deploy.sh up`.
- Volume: `translate_lt-models` (models, re-downloadable).
