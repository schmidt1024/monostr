#!/usr/bin/env bash
# Packet capture for the Tor leak test, docs/testing/tor-leak-test.md. Emulator only, needs a userdebug image (adb root).
#
#   tor-capture.sh prepare            # one network (no Wi-Fi: the emulator's capture sees only eth0), quiet device, empty resolver cache
#   tor-capture.sh flush              # empties netd's cache: a new network has a new one (ndc has no flush on API 35)
#   tor-capture.sh resolve            # what the forbidden names resolve to, from this host
#   tor-capture.sh consensus <apk>    # Tor's relays from Onionoo, from this host, and the ones built into the APK's libtor.so
#   tor-capture.sh start <dir>/<name> # capture outside the guest + the app UID's packets inside it
#   tor-capture.sh stop  <dir>/<name>
#   tor-capture.sh scenario <dir>/<name> <test> [am instrument args]
#   tor-capture.sh flip <dir>/<name> <test> [am instrument args]   # a test the real end of the process stops, then the new process until Connected
#   tor-capture.sh connected <seconds>   # waits until the visible app shows the green onion
#   tor-capture.sh pid                   # the app's main process
#   tor-capture.sh mark <name>           # a mark from the host, for what has no test process
#   tor-capture.sh uiflip <dir>/<name> [edit] # logged in by ui_prepare, the real Settings: `edit` removes a relay right before, then Tor on, restart, Connected
#   tor-capture.sh analyse <dir>/<name> [--after mark] [--until mark] [--baseline pcap]
#   tor-capture.sh restore            # Wi-Fi and the services back
set -euo pipefail
SERIAL=${ANDROID_SERIAL:-emulator-5554}
PKG=${LEAK_PACKAGE:-com.monostr.app.debug}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${LEAK_WORK:-${TMPDIR:-/tmp}/tor-leak}
NFLOG_GROUP=42
RUNNER=com.monostr.app.MonostrTestRunner
ACTIVITY=com.monostr.app.MainActivity
# rust-nostr's late dial came 8 to 13 s after the switch (F1): the capture goes on longer than that
AFTER_FLIP_S=${LEAK_AFTER_FLIP_S:-18}
A() { adb -s "$SERIAL" "$@" </dev/null; }
S() { A shell "$@"; }

# every host the app can reach by itself, plus the hosts the scenarios use
NAMES='
relay.monostr.com
media.monostr.com
watcher.monostr.com
translate.monostr.com
search.monostr.com
monostr.com
relay.damus.io
nos.lol
relay.primal.net
purplepag.es
indexer.coracle.social
user.kindpag.es
search.nos.today
relay.snort.social
offchain.pub
nostr.mom
nostr.build
image.nostr.build
blossom.primal.net
xmr.usenostr.org
nostr.xmr.rocks
nerostr.xmr.rocks
xmr.ithurtswhenip.ee
auth.nostr1.com
upload.wikimedia.org
en.wikipedia.org
wikipedia.org
samplelib.com
download.samplelib.com
httpbin.org
httpbingo.org
nghttp2.org
httpbun.com
postman-echo.com
w3schools.com
www.w3schools.com
test-videos.co.uk
nip.io
onion
'

case "${1:-}" in
prepare)
    A root >/dev/null; sleep 2
    S svc wifi disable
    S settings put global captive_portal_mode 0
    S settings put global captive_portal_detection_enabled 0
    S settings put global auto_time 0
    S settings put global auto_time_zone 0
    S settings put global network_recommendations_enabled 0
    S settings put global ntp_server 127.0.0.1
    S settings put secure location_mode 0
    S settings put global private_dns_mode off
    for p in com.google.android.gms com.google.android.gsf com.android.vending com.google.android.apps.messaging com.google.android.youtube com.google.android.apps.maps com.android.chrome com.google.android.googlequicksearchbox com.google.android.apps.wellbeing com.google.android.apps.photos com.google.android.gm com.google.android.apps.docs com.google.android.apps.youtube.music com.google.android.videos com.google.android.tts com.google.android.partnersetup com.google.android.configupdater com.google.android.syncadapters.calendar com.google.android.syncadapters.contacts com.google.android.onetimeinitializer com.google.android.ext.services com.google.android.apps.restore com.google.android.apps.nbu.files com.google.android.projection.gearhead; do
        S pm disable-user --user 0 "$p" >/dev/null 2>&1 || true
    done
    "$0" flush
    S ip route
    if S ip route | grep -q wlan0; then echo "wlan0 still has a route: the capture would not see it" >&2; exit 1; fi
    ;;
flush)
    S cmd connectivity airplane-mode enable
    sleep 3
    S cmd connectivity airplane-mode disable
    sleep 8
    ;;
restore)
    A root >/dev/null; sleep 2
    for p in com.google.android.gms com.google.android.gsf com.android.vending com.google.android.apps.messaging com.google.android.youtube com.google.android.apps.maps com.android.chrome com.google.android.googlequicksearchbox com.google.android.apps.wellbeing com.google.android.apps.photos com.google.android.gm com.google.android.apps.docs com.google.android.apps.youtube.music com.google.android.videos com.google.android.tts com.google.android.partnersetup com.google.android.configupdater com.google.android.syncadapters.calendar com.google.android.syncadapters.contacts com.google.android.onetimeinitializer com.google.android.ext.services com.google.android.apps.restore com.google.android.apps.nbu.files com.google.android.projection.gearhead; do
        S pm enable "$p" >/dev/null 2>&1 || true
    done
    S settings delete global captive_portal_mode || true
    S settings delete global captive_portal_detection_enabled || true
    S settings put global auto_time 1
    S settings put global auto_time_zone 1
    S settings delete global ntp_server || true
    S settings put secure location_mode 3 || true
    S settings delete global private_dns_mode || true
    S svc wifi enable
    ;;
resolve)
    mkdir -p "$WORK"
    printf '%s\n' $NAMES > "$WORK/names.txt"
    python3 - "$WORK/names.txt" "$WORK/ips.json" <<'EOF'
import json, socket, sys
out = {}
for name in open(sys.argv[1]).read().split():
    # the answers vary per lookup behind a CDN, so ask a few times
    for _ in range(4):
        try:
            for info in socket.getaddrinfo(name, 443, proto=socket.IPPROTO_TCP):
                out[info[4][0]] = name
        except OSError:
            print("cannot resolve", name, file=sys.stderr)
            break
json.dump(out, open(sys.argv[2], "w"), indent=1)
print(len(out), "addresses for", len(set(out.values())), "names")
EOF
    ;;
consensus)
    mkdir -p "$WORK"
    python3 - "$WORK/consensus.json" ${2:-} <<'EOF'
import ipaddress, json, re, sys, urllib.request, zipfile
# without running=true: Onionoo's list also holds relays that left in the last week, and a cached Tor may still try those
req = urllib.request.Request("https://onionoo.torproject.org/details?type=relay&fields=or_addresses,running", headers={"User-Agent": "monostr-leak-test"})
data = json.load(urllib.request.urlopen(req, timeout=120))
out = {}
for relay in data["relays"]:
    for addr in relay.get("or_addresses", []):
        host, port = addr.rsplit(":", 1)
        host = str(ipaddress.ip_address(host.strip("[]")))
        out.setdefault(host, []).append(int(port))
# what the app's Tor has built in may have left the network long ago: its fallback directories and authorities, from libtor.so
built_in = 0
if len(sys.argv) > 2:
    with zipfile.ZipFile(sys.argv[2]) as apk:
        lib = next(n for n in apk.namelist() if n.endswith("/libtor.so"))
        text = apk.read(lib).decode("latin-1")
    for m in re.finditer(r'(?:^|[\x00"])(\d+\.\d+\.\d+\.\d+) orport=(\d+) id=', text):
        out.setdefault(m.group(1), []).append(int(m.group(2))); built_in += 1
    for m in re.finditer(r'orport=(\d+)[^\x00]{0,200}? (\d+\.\d+\.\d+\.\d+):\d+ ', text):
        out.setdefault(m.group(2), []).append(int(m.group(1))); built_in += 1
json.dump(out, open(sys.argv[1], "w"))
print(len(data["relays"]), "relays,", len(out), "addresses, published", data.get("relays_published"), "; built into libtor:", built_in)
EOF
    ;;
start)
    base=$2; mkdir -p "$(dirname "$base")"
    A root >/dev/null; sleep 1
    A emu network capture start "$base.pcap" >/dev/null
    uid=$(S pm list packages -U "$PKG" | tr -d '\r' | sed -n "s/^package:$PKG uid://p" | head -1 || true)
    if [ -n "$uid" ]; then
        S "iptables -D OUTPUT ! -o lo -m owner --uid-owner $uid -j NFLOG --nflog-group $NFLOG_GROUP 2>/dev/null; ip6tables -D OUTPUT ! -o lo -m owner --uid-owner $uid -j NFLOG --nflog-group $NFLOG_GROUP 2>/dev/null; iptables -I OUTPUT ! -o lo -m owner --uid-owner $uid -j NFLOG --nflog-group $NFLOG_GROUP; ip6tables -I OUTPUT ! -o lo -m owner --uid-owner $uid -j NFLOG --nflog-group $NFLOG_GROUP; iptables -D OUTPUT -d 192.0.2.1 -m owner --uid-owner 0 -j NFLOG --nflog-group $NFLOG_GROUP 2>/dev/null; iptables -I OUTPUT -d 192.0.2.1 -m owner --uid-owner 0 -j NFLOG --nflog-group $NFLOG_GROUP"
        A shell "nohup sh -c 'tcpdump -U -i nflog:$NFLOG_GROUP -w /data/local/tmp/leak-app.pcap' >/dev/null 2>&1 &"
    fi
    A logcat -c || true
    A logcat -c -b crash || true
    ;;
stop)
    base=$2
    A emu network capture stop >/dev/null || true
    S "pkill -INT tcpdump; sleep 0.5" || true
    A pull /data/local/tmp/leak-app.pcap "$base.app.pcap" >/dev/null 2>&1 || true
    S rm -f /data/local/tmp/leak-app.pcap || true
    A logcat -d -s TorLeak:* TorController:* > "$base.log" || true
    ;;
scenario)
    # one test of TorLeakScenarios with a capture around it; extra arguments go to `am instrument`
    base=$2; test=$3; shift 3
    "$0" start "$base"
    S am instrument -w -r -e leak true "$@" -e class "com.monostr.app.TorLeakScenarios#$test" "$PKG.test/$RUNNER" > "$base.instrument" 2>&1 || true
    sleep 2
    "$0" stop "$base"
    grep -E 'OK \(|FAILURES|Tests run|shortMsg' "$base.instrument" || true
    ;;
pid)
    S pidof "$PKG" | tr -d '\r'
    ;;
connected)
    # the top bar's description of the onion, read from outside the app. The instrumentation's end force-stops the package, so a start of ours may be needed first
    until=$(( $(date +%s) + ${2:-180} ))
    want=$(grep -oP '(?<=name="tor_desc_connected">)[^<]*' "$HERE/../android/app/src/main/res/values/strings.xml")
    while [ "$(date +%s)" -lt "$until" ]; do
        if S "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml" | grep -qF "content-desc=\"$want\""; then echo connected; exit 0; fi
        # the offer a new account gets covers the top bar, and it can come late
        "$HERE/tor_ui.py" has Later 2>/dev/null && { "$HERE/tor_ui.py" tap Later 2 2>/dev/null || true; }
        sleep 2
    done
    echo "not connected after ${2:-180} s"; exit 1
    ;;
mark)
    # as root: Android blocks the app's UID once its process is gone. `start` logs root's packets to TEST-NET too. toybox's nc does not end by itself on UDP
    S "echo -n TORLEAK:$2 | timeout 0.3 nc -u 192.0.2.1 9" || true
    ;;
uiflip)
    # No instrumentation around the switch: the real restart of the real app, the activity it starts itself included.
    base=$2; edit=${3:-}; U=$HERE/tor_ui.py
    "$0" start "$base"
    S pm clear "$PKG" >/dev/null
    S am instrument -w -r -e leak true -e keep true -e class "com.monostr.app.TorLeakScenarios#ui_prepare" "$PKG.test/$RUNNER" > "$base.instrument" 2>&1 || true
    S am start -W -n "$PKG/$ACTIVITY" >/dev/null
    "$U" tap Later 15 2>/dev/null || true
    "$U" tap Settings 15
    sleep 1
    "$U" scroll Relays 15
    "$U" wait connected 40
    [ "$edit" != edit ] || "$U" tap Remove 5
    "$U" tap "Connect through Tor" 5
    "$U" wait "Monostr restarts once." 5 && echo "sentence shown" | tee "$base.ui"
    old=$("$0" pid)
    "$U" tap "Turn on" 5
    "$0" mark flip
    flipped=$(date +%s)
    new=$old
    while [ "$new" = "$old" ] && [ $(( $(date +%s) - flipped )) -lt 20 ]; do new=$("$0" pid); sleep 0.2; done
    "$U" wait "Connect through Tor" 30 && echo "back in Settings at Privacy after $(( $(date +%s) - flipped )) s" | tee -a "$base.ui"
    "$U" wait Connected 180 && echo "Settings row Connected after $(( $(date +%s) - flipped )) s" | tee -a "$base.ui"
    "$U" wait connected 60 && echo "a relay connected after $(( $(date +%s) - flipped )) s" | tee -a "$base.ui"
    "$U" texts > "$base.texts"
    left=$(( flipped + AFTER_FLIP_S - $(date +%s) ))
    [ "$left" -le 0 ] || sleep "$left"
    "$U" tap Back 5
    "$0" connected 30 | tee -a "$base.ui"
    new=$("$0" pid)
    A logcat -d -b crash > "$base.crash" || true
    A logcat -d | grep -E "FATAL|ANR in|has died|Process $PKG .*died" > "$base.fatal" || true
    echo "pid $old -> $new" | tee "$base.pid"
    "$0" stop "$base"
    ;;
flip)
    # The app and its instrumentation die together, and the system then force-stops the package, which also takes the activity the app restarted.
    # So here the host starts the app, which is what a user would see anyway apart from the moment; tests of that moment are in the doc, through the UI.
    base=$2; test=$3; shift 3
    "$0" start "$base"
    S am instrument -w -r -e leak true -e realEnd true "$@" -e class "com.monostr.app.TorLeakScenarios#$test" "$PKG.test/$RUNNER" > "$base.instrument" 2>&1 || true
    flipped=$(date +%s)
    old=$(A logcat -d -s TorLeak:* | tr -d '\r' | sed -n 's/.*result pid //p' | tail -1)
    S am start -W -n "$PKG/$ACTIVITY" >/dev/null || true
    "$0" connected "${LEAK_CONNECT_S:-180}" | tee "$base.connected" || true
    left=$(( flipped + AFTER_FLIP_S - $(date +%s) ))
    [ "$left" -le 0 ] || sleep "$left"
    new=$("$0" pid)
    "$0" stop "$base"
    echo "pid $old -> $new" | tee "$base.pid"
    grep -E 'OK \(|FAILURES|Tests run|shortMsg' "$base.instrument" || true
    ;;
analyse)
    base=$2; shift 2
    app=(); [ -s "$base.app.pcap" ] && app=(--app-pcap "$base.app.pcap")
    python3 "$HERE/tor_capture_analyse.py" "$base.pcap" --names "$WORK/names.txt" --ips "$WORK/ips.json" --consensus "$WORK/consensus.json" \
        "${app[@]}" --json "$base.json" "$@"
    ;;
*)
    sed -n 2,12p "$0"; exit 2 ;;
esac
