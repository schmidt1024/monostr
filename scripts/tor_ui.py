#!/usr/bin/env python3
"""Drives the real app from the host, for what has no instrumentation around it: the restart after switching to Tor.
Emulator only. Finds nodes by text or content description through uiautomator.

  tor_ui.py wait <text> [seconds]     exit 1 when it does not come
  tor_ui.py tap <text> [seconds]
  tor_ui.py scroll <text> [swipes]    swipes up until the text shows, then taps nothing
  tor_ui.py has <text>                exit 0 or 1
  tor_ui.py type <text>
  tor_ui.py texts                     everything shown, one per line
"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")


def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60).stdout


def nodes():
    out = adb("exec-out", "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml")
    start = out.find("<?xml")
    if start < 0:
        return []
    try:
        return list(ET.fromstring(out[start:]).iter("node"))
    except ET.ParseError:
        return []


def find(text):
    for node in nodes():
        if text in (node.get("text"), node.get("content-desc")):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def wait(text, seconds):
    until = time.time() + seconds
    while time.time() < until:
        at = find(text)
        if at:
            return at
        time.sleep(0.5)
    sys.exit(f"not shown after {seconds} s: {text}")


def main():
    cmd, *rest = sys.argv[1:]
    if cmd == "wait":
        wait(rest[0], float(rest[1]) if len(rest) > 1 else 30)
    elif cmd == "tap":
        x, y = wait(rest[0], float(rest[1]) if len(rest) > 1 else 30)
        adb("shell", "input", "tap", str(x), str(y))
    elif cmd == "scroll":
        for _ in range(int(rest[1]) if len(rest) > 1 else 15):
            if find(rest[0]):
                return
            adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
            time.sleep(0.4)
        sys.exit(f"not found by scrolling: {rest[0]}")
    elif cmd == "has":
        sys.exit(0 if find(rest[0]) else 1)
    elif cmd == "type":
        adb("shell", "input", "text", rest[0])
    elif cmd == "texts":
        for node in nodes():
            for key in ("text", "content-desc"):
                if node.get(key):
                    print(node.get(key))


main()
