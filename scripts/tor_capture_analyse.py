#!/usr/bin/env python3
"""Reads a pcap of the emulator's network and says whether anything left outside Tor.

Standard library only. See docs/testing/tor-leak-test.md for the method.

FINDING (exit code 1):
  - a DNS query (UDP or TCP 53, DoT 853) for a name in the forbidden list, whoever sent it;
  - a TCP SYN or data to an address those names resolve to, or a TLS ClientHello naming one;
  - with --app-pcap (packets of the app's UID): any DNS, any UDP, or TCP to an address that is not a Tor relay;
    TCP to a private or reserved address, loopback aside, whatever its packets.
REVIEW (listed, no exit code): every other DNS name and every other remote address, each with its class
  against the consensus and whether the idle baseline has it too.

The test process marks its steps with a UDP packet to 192.0.2.1:9 (TEST-NET, nothing answers), payload
TORLEAK:<name>. --after and --until cut the capture at those marks. A relay connection that was open before
--after may still send its closing packets: such a flow with no SYN, ended within --teardown seconds after
the mark, is listed as TEARDOWN; one lasting longer is a finding.
"""
import argparse
import ipaddress
import json
import socket
import struct
import sys
from collections import Counter, defaultdict

MARK_IP = "192.0.2.1"
MARK_PREFIX = b"TORLEAK:"
LINK_ETHERNET, LINK_RAW, LINK_SLL, LINK_NFLOG, LINK_SLL2 = 1, 101, 113, 239, 276


def read_packets(path):
    """Yields (timestamp, ip packet bytes)."""
    with open(path, "rb") as f:
        head = f.read(24)
        if len(head) < 24:
            return
        magic = head[:4]
        if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
            endian = "<"
        elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
            endian = ">"
        else:
            raise SystemExit(f"{path}: not a pcap file")
        nano = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
        link = struct.unpack(endian + "I", head[20:24])[0] & 0xFFFF
        while True:
            rec = f.read(16)
            if len(rec) < 16:
                return
            sec, frac, incl, _ = struct.unpack(endian + "IIII", rec)
            data = f.read(incl)
            if len(data) < incl:
                return
            ip = strip_link(link, data)
            if ip is not None:
                yield sec + frac / (1e9 if nano else 1e6), ip


def strip_link(link, data):
    if link == LINK_RAW:
        return data
    if link == LINK_ETHERNET:
        pos, proto = 14, data[12:14]
        while proto == b"\x81\x00":
            proto, pos = data[pos + 2:pos + 4], pos + 4
        return data[pos:] if proto in (b"\x08\x00", b"\x86\xdd") else None
    if link == LINK_SLL:
        return data[16:] if data[14:16] in (b"\x08\x00", b"\x86\xdd") else None
    if link == LINK_SLL2:
        return data[20:] if data[0:2] in (b"\x08\x00", b"\x86\xdd") else None
    if link == LINK_NFLOG:
        pos = 4
        while pos + 4 <= len(data):
            length, kind = struct.unpack("=HH", data[pos:pos + 4])
            if length < 4:
                return None
            if kind == 9:
                return data[pos + 4:pos + length]
            pos += (length + 3) & ~3
        return None
    raise SystemExit(f"unknown link type {link}")


def parse_ip(ip):
    """(version, src, dst, proto, payload) or None."""
    if len(ip) < 20:
        return None
    version = ip[0] >> 4
    if version == 4:
        ihl = (ip[0] & 15) * 4
        total = struct.unpack("!H", ip[2:4])[0]
        if struct.unpack("!H", ip[6:8])[0] & 0x1FFF:
            return None  # a later fragment
        return 4, socket.inet_ntoa(ip[12:16]), socket.inet_ntoa(ip[16:20]), ip[9], ip[ihl:total or len(ip)]
    if version == 6 and len(ip) >= 40:
        proto, pos = ip[6], 40
        while proto in (0, 43, 60) and pos + 8 <= len(ip):
            proto, pos = ip[pos], pos + (ip[pos + 1] + 1) * 8
        src = socket.inet_ntop(socket.AF_INET6, ip[8:24])
        dst = socket.inet_ntop(socket.AF_INET6, ip[24:40])
        return 6, src, dst, proto, ip[pos:]
    return None


def is_public(addr):
    a = ipaddress.ip_address(addr)
    if a.is_multicast or a.is_loopback or a.is_link_local or a.is_unspecified:
        return False
    return not a.is_private or addr == MARK_IP


def is_local_range(addr):
    """Private or reserved, loopback aside: nothing here is a Tor relay, and the app's UID has no business there. Marks are UDP and do not count."""
    a = ipaddress.ip_address(addr)
    return not a.is_loopback and not is_public(addr)


def dns_names(payload):
    """The question names of one DNS message, as (name, type)."""
    if len(payload) < 12:
        return []
    count = struct.unpack("!H", payload[4:6])[0]
    out, pos = [], 12
    for _ in range(min(count, 8)):
        labels = []
        while pos < len(payload):
            n = payload[pos]
            if n == 0 or n >= 0xC0:
                pos += 1 if n == 0 else 2
                break
            labels.append(payload[pos + 1:pos + 1 + n].decode("ascii", "replace"))
            pos += n + 1
        if pos + 4 > len(payload):
            break
        out.append((".".join(labels).lower(), struct.unpack("!H", payload[pos:pos + 2])[0]))
        pos += 4
    return out


def tls_sni(p):
    """The server name of a TLS ClientHello that starts in this segment, or None."""
    try:
        if len(p) < 44 or p[0] != 22 or p[5] != 1:
            return None
        pos = 43
        pos += 1 + p[pos]
        pos += 2 + struct.unpack("!H", p[pos:pos + 2])[0]
        pos += 1 + p[pos]
        end = pos + 2 + struct.unpack("!H", p[pos:pos + 2])[0]
        pos += 2
        while pos + 4 <= min(end, len(p)):
            kind, length = struct.unpack("!HH", p[pos:pos + 4])
            if kind == 0:
                n = struct.unpack("!H", p[pos + 7:pos + 9])[0]
                return p[pos + 9:pos + 9 + n].decode("ascii", "replace").lower()
            pos += 4 + length
    except (IndexError, struct.error):
        pass
    return None


class Flow:
    def __init__(self, t):
        self.first = self.last = t
        self.syn = self.out_pkts = self.in_pkts = self.out_bytes = self.in_bytes = self.out_data = 0
        self.snis = set()
        self.after = "-"


def load(path):
    """Marks and packets of one capture, in file order."""
    packets, marks = [], []
    for t, raw in read_packets(path):
        parsed = parse_ip(raw)
        if parsed is None:
            continue
        version, src, dst, proto, payload = parsed
        if proto == 17 and dst == MARK_IP and len(payload) >= 8 and payload[8:].startswith(MARK_PREFIX):
            marks.append((t, payload[8 + len(MARK_PREFIX):].decode("ascii", "replace")))
            continue
        packets.append((t, version, src, dst, proto, payload))
    return packets, marks


def cut(packets, marks, after, until):
    start, end = None, None
    if after is not None:
        hit = [t for t, name in marks if name == after]
        if not hit:
            raise SystemExit(f"mark {after!r} not in the capture; marks: {[n for _, n in marks]}")
        start = hit[0]
    if until is not None:
        hit = [t for t, name in marks if name == until and (start is None or t >= start)]
        if not hit:
            raise SystemExit(f"mark {until!r} not in the capture after the start")
        end = hit[0]
    return [p for p in packets if (start is None or p[0] >= start) and (end is None or p[0] <= end)], start


def summarise(packets, marks):
    """Per remote endpoint and per DNS name, from the guest's point of view."""
    dns, tcp, udp, other = Counter(), {}, {}, Counter()
    order = sorted(marks)
    def after_mark(t):
        name = "-"
        for mt, mn in order:
            if mt <= t:
                name = mn
        return name
    for t, _, src, dst, proto, payload in packets:
        outgoing = not is_public(src)
        remote, _local = (dst, src) if outgoing else (src, dst)
        if proto in (6, 17) and len(payload) >= 8:
            sport, dport = struct.unpack("!HH", payload[:4])
            rport = dport if outgoing else sport
            if proto == 17 and rport == 5353 or dport == 5353:
                continue  # mDNS of the OS, on the local link
            if rport in (53, 853) or dport == 53:
                body = payload[8:] if proto == 17 else payload[(payload[12] >> 4) * 4 + 2:] if len(payload) >= 20 else b""
                if outgoing:
                    if rport == 853:
                        dns[("<dot>", dst)] += 1
                    for name, kind in dns_names(body):
                        dns[(name, kind)] += 1
                continue
            if not is_public(remote) and not (proto == 6 and is_local_range(remote)):
                continue
            table = tcp if proto == 6 else udp
            flow = table.get((remote, rport))
            if flow is None:
                flow = table[(remote, rport)] = Flow(t)
                flow.after = after_mark(t)
            flow.last = t
            if outgoing:
                flow.out_pkts += 1
                flow.out_bytes += len(payload)
            else:
                flow.in_pkts += 1
                flow.in_bytes += len(payload)
            if proto == 6 and len(payload) >= 20:
                flags = payload[13]
                if flags & 0x02 and not flags & 0x10 and outgoing:
                    flow.syn += 1
                body = payload[(payload[12] >> 4) * 4:]
                if outgoing and body:
                    flow.out_data += len(body)
                    sni = tls_sni(body)
                    if sni:
                        flow.snis.add(sni)
        elif is_public(remote):
            other[(remote, proto)] += 1
    return dns, tcp, udp, other


def matches(name, forbidden):
    return any(name == f or name.endswith("." + f) for f in forbidden)


def relay_class(ip, port, relays):
    if ip not in relays:
        return "not-a-tor-relay"
    return "tor-relay" if port in relays[ip] else "tor-relay-ip-other-port"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pcap")
    ap.add_argument("--names", required=True, help="file: forbidden names, one per line")
    ap.add_argument("--ips", required=True, help="json: address -> name, what those names resolved to")
    ap.add_argument("--consensus", required=True, help="json: address -> [or ports], from tor-capture.sh consensus")
    ap.add_argument("--baseline", help="pcap of the idle device with the app stopped")
    ap.add_argument("--app-pcap", help="nflog pcap of the app's UID, from inside the guest")
    ap.add_argument("--after", help="start at this mark")
    ap.add_argument("--until", help="end at this mark")
    ap.add_argument("--teardown", type=float, default=5.0)
    ap.add_argument("--json", help="write the result here")
    args = ap.parse_args()

    forbidden = {l.strip().lower() for l in open(args.names) if l.strip() and not l.startswith("#")}
    forbidden_ips = json.load(open(args.ips))
    relays = {k: set(v) for k, v in json.load(open(args.consensus)).items()}

    packets, marks = load(args.pcap)
    sliced, start = cut(packets, marks, args.after, args.until)
    dns, tcp, udp, other = summarise(sliced, marks)

    base_dns, base_ep = set(), set()
    if args.baseline:
        b_packets, b_marks = load(args.baseline)
        d, t, u, _ = summarise(b_packets, b_marks)
        base_dns = {k[0] for k in d}
        base_ep = {k for k in t} | {k for k in u}

    findings, review, teardown = [], [], []
    def is_teardown(f, t0):
        # no new connection, nothing written beyond a TLS close_notify, gone right after the mark
        return f.syn == 0 and f.out_data <= 64 and t0 is not None and f.last - t0 <= args.teardown
    def note(kind, text):
        findings.append(f"{kind}: {text}")

    for (name, kind), n in sorted(dns.items(), key=str):
        if name == "<dot>":
            review.append(f"DNS-over-TLS packets to {kind}: {n}")
        elif matches(name, forbidden):
            note("DNS", f"{name} type {kind} x{n}" + ("  (also in the idle baseline)" if name in base_dns else ""))
        else:
            review.append(f"DNS name {name} type {kind} x{n}" + ("  [in baseline]" if name in base_dns else "  [NOT in baseline]"))

    for (ip, port), f in sorted(tcp.items()):
        desc = f"{ip}:{port} syn={f.syn} out={f.out_pkts}/{f.out_bytes}B in={f.in_pkts}/{f.in_bytes}B first-after={f.after} sni={sorted(f.snis) or '-'}"
        bad_sni = [s for s in f.snis if matches(s, forbidden)]
        if ip in forbidden_ips:
            if is_teardown(f, start):
                teardown.append(f"{forbidden_ips[ip]} {desc} data-out={f.out_data}B last {f.last - start:.2f}s after the mark")
            else:
                note("TCP", f"{forbidden_ips[ip]} {desc}")
        elif bad_sni:
            note("SNI", desc)
        elif ip.startswith("10.0.2."):
            review.append(f"host side {desc}")
        else:
            cls = relay_class(ip, port, relays)
            review.append(f"tcp {cls} {desc}" + ("  [in baseline]" if (ip, port) in base_ep else ""))

    for (ip, port), f in sorted(udp.items()):
        desc = f"{ip}:{port} out={f.out_pkts}/{f.out_bytes}B in={f.in_pkts}/{f.in_bytes}B first-after={f.after}"
        if ip in forbidden_ips:
            note("UDP", f"{forbidden_ips[ip]} {desc}")
        else:
            review.append(f"udp {desc}" + ("  [in baseline]" if (ip, port) in base_ep else ""))

    for (ip, proto), n in sorted(other.items(), key=str):
        (note if ip in forbidden_ips else lambda k, t: review.append(f"{k} {t}"))("IP", f"{ip} protocol {proto} x{n}")

    app = None
    if args.app_pcap:
        a_packets, a_marks = load(args.app_pcap)
        a_sliced, a_start = a_packets, None
        if start is not None or args.until:
            # the guest's clock differs from the pcap's: its own marks cut it
            a_sliced, a_start = cut(a_packets, a_marks, args.after, args.until)
        a_dns, a_tcp, a_udp, a_other = summarise(a_sliced, a_marks)
        app = {"dns": sum(a_dns.values()), "tcp": {}, "udp": len(a_udp)}
        for (ip, port), f in sorted(a_tcp.items()):
            cls = relay_class(ip, port, relays)
            app["tcp"][f"{ip}:{port}"] = cls
            if is_local_range(ip):
                # no closing packets aside here: it should not have been open at all
                note("APP-UID-TCP-LOCAL", f"{ip}:{port} syn={f.syn} out={f.out_pkts}/{f.out_bytes}B first-after={f.after} sni={sorted(f.snis) or '-'}")
            elif cls != "tor-relay" and not is_teardown(f, a_start):
                note("APP-UID-TCP", f"{cls} {ip}:{port} syn={f.syn} out={f.out_pkts}/{f.out_bytes}B first-after={f.after} sni={sorted(f.snis) or '-'}")
        for (name, kind), n in sorted(a_dns.items(), key=str):
            note("APP-UID-DNS", f"{name} {kind} x{n}")
        for (ip, port), f in sorted(a_udp.items()):
            note("APP-UID-UDP", f"{ip}:{port} out={f.out_pkts}/{f.out_bytes}B")
        for (ip, proto), n in sorted(a_other.items(), key=str):
            note("APP-UID-IP", f"{ip} protocol {proto} x{n}")

    print(f"capture {args.pcap}: {len(packets)} packets, {len(sliced)} in the slice, marks: {[n for _, n in sorted(marks)]}")
    print(f"--- FINDINGS: {len(findings)}")
    for line in findings:
        print("  " + line)
    if teardown:
        print(f"--- TEARDOWN of connections from before the mark: {len(teardown)}")
        for line in teardown:
            print("  " + line)
    if app is not None:
        tor = sum(1 for v in app["tcp"].values() if v == "tor-relay")
        print(f"--- APP UID: {tor}/{len(app['tcp'])} TCP endpoints are Tor relays, DNS {app['dns']}, UDP {app['udp']}")
    print(f"--- REVIEW: {len(review)}")
    for line in review:
        print("  " + line)
    if args.json:
        json.dump({"findings": findings, "teardown": teardown, "review": review, "app": app, "marks": [n for _, n in sorted(marks)]}, open(args.json, "w"), indent=1)
    sys.exit(1 if findings else 0)


if __name__ == "__main__":
    main()
