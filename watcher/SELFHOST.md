# Run your own watcher

The watcher holds the private view key of your tip wallet, detects incoming tips and publishes the
signed receipts on Nostr. `watcher.monostr.com` does this for anyone; if you run your own, nobody
else sees your view key or your incoming payments.

This setup runs everything on one host with Docker Compose: a pruned Monero node (`monerod`), the
scanner `monero-lws`, the watcher, and Caddy for HTTPS.

## Personal watcher (no node, no domain)

The simplest way: a small always-on machine (a Raspberry Pi 4/5, a NAS with Docker, any VPS)
watches **your own** tip wallet. Your view key never leaves that machine. It scans through public
Monero nodes, so there is no blockchain to download, and it only connects outward: no domain, no
open port, it works behind a home router.

You need Docker with the Compose plugin, about 1 GB of disk and about 1.5 GB of RAM, and
monostr 0.17 or newer.

```bash
git clone https://github.com/schmidt1024/monostr.git
cd monostr/watcher
cp .env.personal.example .env
chmod 600 .env
sed -i "s/^WATCHER_SECRET_KEY=.*/WATCHER_SECRET_KEY=$(openssl rand -hex 32)/" .env
```

Fill in `.env`: your npub (`WATCHER_RECIPIENT`), the primary address and private view key of your
tip wallet (Cake, Monerujo and Feather show both under the wallet's keys). Then:

```bash
docker compose -f compose.personal.yml up -d --build
docker compose -f compose.personal.yml logs watcher | grep nprofile
```

Copy the `nprofile1…` value. In monostr: **Settings → Monero tips → Switch watcher → Personal
watcher** (or **Set up receiving Monero tips**), paste it and confirm. The app checks that the
watcher is online and set up for your account (it takes the address from the watcher), then
announces it in your profile.

How it stays honest: every payment must also show up, with the same amount, in a second wallet that
syncs through a different public node, so a single lying node cannot invent tips (it can still
withhold one; the payment then arrives in your wallet without a receipt). To hide your IP address
from relays and nodes, uncomment the two Tor lines in `.env` and run
`docker compose -f compose.personal.yml up -d` again. If you run the watcher without this compose
file, `WALLET_RPC_URL` and `WALLET_RPC_CHECK_URL` must point to two different wallet-rpc containers.

If the machine is off, tips still arrive in your wallet; the receipts follow when it is back.
Updates: `git pull`, then `docker compose -f compose.personal.yml up -d --build`. Back up `.env`.

## Own node and domain (for hosting a watcher)

### What you need

- A Linux server with Docker and the Compose plugin, reachable from the internet on ports 80 and 443
  (18080 for Monero P2P is recommended).
- **Disk:** about 110 GB for the pruned blockchain today; plan for 150 GB. An SSD makes the first
  sync much faster.
- **RAM:** 4 GB (2 GB plus swap works, slower).
- A domain or subdomain whose DNS record (A/AAAA) points to the server, e.g. `watcher.example.com`.
- Time: the first blockchain sync takes from several hours to a few days.

If you already run a Monero node, see [Using an existing node](#using-an-existing-node).

### Setup

```bash
git clone https://github.com/schmidt1024/monostr.git
cd monostr/watcher
cp .env.selfhost.example .env
chmod 600 .env
sed -i "s/^WATCHER_SECRET_KEY=.*/WATCHER_SECRET_KEY=$(openssl rand -hex 32)/" .env
```

Edit `.env`:

- `WATCHER_DOMAIN`: your domain, e.g. `watcher.example.com`.
- `WATCHER_ALLOWED_PUBKEYS`: your `npub` (several separated by commas). Only these accounts can
  register a wallet. Leave it empty only if you want to offer the watcher to others.
- `WATCHER_RELAYS` (optional): the relays the watcher reads tip intents from and publishes receipts
  to. The default is fine.

Start:

```bash
docker compose -f compose.selfhost.yml up -d --build
curl https://watcher.example.com/v1/info
```

`/v1/info` answers right away with the watcher's pubkey, network and `height`. Keep the secret key
in `.env` safe: it is the watcher's identity, and receipts are signed with it.

### Wait for the sync

```bash
docker compose -f compose.selfhost.yml logs -f monerod
```

monerod is done when it logs `You are now synchronized with the network`. Then `height` in
`/v1/info` is close to the current block height (compare with any block explorer). Switch the app
to your watcher only after that.

### Switch the app to your watcher

In monostr: **Settings → Monero tips → Switch watcher** (or **Set up receiving Monero tips** if you
have not set up tips yet), enter `https://watcher.example.com` as the watcher URL and finish the
setup. The app removes your account from the previous watcher and announces the new one in your
profile, so senders use it from then on.

Use a wallet only for tips: the view key shows the watcher every incoming payment to that wallet,
not only tips.

### Updates

```bash
cd monostr && git pull
cd watcher && docker compose -f compose.selfhost.yml up -d --build
```

The images of monerod, monero-lws and Caddy are pinned by digest in `compose.selfhost.yml`; a
`git pull` brings tested new versions.

### Backup

Back up `.env` (secret key). The watcher database lives in the volume
`monostr-watcher_watcher-data` (`watcher.db`, SQLite) and holds open tips and receipts. Losing it
is not fatal: on the next start the watcher reads the last day's tip intents from the relays again
and takes over the receipts it had already published instead of publishing them twice. Tips older
than a day that were still open are lost. Wallets registered in monero-lws are added again the next
time the app registers. The blockchain does not need a backup.

### Using an existing node

monero-lws needs monerod's ZMQ interface, which public nodes do not offer. If you run monerod
yourself, start it with:

```
--zmq-rpc-bind-ip=0.0.0.0 --zmq-rpc-bind-port=18082 --confirm-zmq-rpc-external-bind
--zmq-pub=tcp://0.0.0.0:18083
```

Never publish these ports to the internet. Then remove the `monerod` service and the `monerod-data`
volume from your copy of `compose.selfhost.yml`, and point `--daemon`, `--sub` (monero-lws) and
`MONEROD_RPC_URL` (watcher, restricted RPC) at your node, e.g. by attaching its Docker network.

### Troubleshooting

- `/v1/info` does not answer: check the DNS record and that ports 80/443 are open; `docker compose
  -f compose.selfhost.yml logs caddy` shows certificate errors.
- Setup fails with "this watcher only accepts accounts of its operator": your npub is not in
  `WATCHER_ALLOWED_PUBKEYS`. After changing `.env`, run `docker compose -f compose.selfhost.yml up -d`.
- Tips stay unconfirmed: monerod or monero-lws is still syncing; check the logs of both.
