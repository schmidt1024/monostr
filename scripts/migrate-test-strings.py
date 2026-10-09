#!/usr/bin/env python3
"""One-off: rewrite German string assertions in :app JVM tests to UiText resource ids (Plan 6, Task 2)."""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/test/kotlin/com/monostr/app"
TABLE = {
    "Im Signer abgelehnt": "error_signing_rejected",
    "Note nicht verfügbar": "error_note_unavailable",
    "Senden fehlgeschlagen": "error_send_failed",
    "Watcher nicht erreichbar": "error_watcher_unreachable",
    "Watcher hat die Anfrage abgelehnt": "error_watcher_rejected",
    "Watcher-Antwort ungültig": "error_watcher_invalid",
    "Like gesendet": "action_like_sent",
    "Repost gesendet": "action_repost_sent",
    "Kein Relay erreichbar, wird lokal gehalten": "action_no_relay_local",
    "Feed konnte nicht geladen werden": "feed_error_load",
    "Weitere Notes konnten nicht geladen werden": "feed_error_more",
    "Profil konnte nicht geladen werden": "profile_error_load",
    "Leere Note": "compose_error_empty",
    "Antwort-Ziel konnte nicht geladen werden": "compose_error_reply_target",
    "Kein Relay erreichbar, Note lokal gespeichert": "compose_notice_offline",
    "Kein gültiger nsec oder Hex-Schlüssel": "login_error_invalid_key",
    "Anfrage im Signer abgelehnt": "login_error_rejected",
    "Signer hat einen Fehler gemeldet": "login_error_signer",
    "Anmeldung fehlgeschlagen": "login_error_failed",
    "Sitzung konnte nicht wiederhergestellt werden": "session_restore_failed",
    "Ungültige Relay-URL (ws:// oder wss:// erwartet)": "settings_relay_invalid",
    "Mindestens ein Relay bleibt": "settings_relay_keep_one",
    "Relays konnten nicht gespeichert werden": "settings_relay_save_failed",
    "Presets gespeichert": "settings_msg_presets_saved",
    "Monero-Tips abgeschaltet": "settings_msg_disabled",
    "View-Key lokal gelöscht": "settings_msg_view_key_deleted",
    "Relays aus dem Profil übernommen": "settings_msg_relays_adopted",
    "Keine Relay-Liste (NIP-65) im Profil gefunden": "settings_msg_no_relay_list",
    "Mitteilungen konnten nicht geladen werden": "notifications_error_load",
    "hat geantwortet": "notif_replied",
    "hat dich erwähnt": "notif_mentioned",
}
# messages with arguments: literal -> (key, kotlin args)
ARGS = {
    "hat reagiert ❤": ("notif_reacted", '"❤"'),
    "hat 0.005 XMR getippt": ("notif_tipped", '"0.005"'),
}
IMPORTS = ["import com.monostr.app.R", "import com.monostr.app.ui.common.uiText"]

changed = 0
for path in sorted(ROOT.rglob("*Test.kt")):
    text = path.read_text(encoding="utf-8")
    original = text
    for literal, key in TABLE.items():
        text = text.replace(f'"{literal}"', f"uiText(R.string.{key})")
    for literal, (key, args) in ARGS.items():
        text = text.replace(f'"{literal}"', f"uiText(R.string.{key}, {args})")
    if text != original:
        lines = text.split("\n")
        pkg = next(i for i, l in enumerate(lines) if l.startswith("package "))
        for imp in IMPORTS:
            if imp not in text:
                lines.insert(pkg + 1, imp)
        text = "\n".join(lines)
        path.write_text(text, encoding="utf-8")
        changed += 1
        print("rewrote", path.relative_to(ROOT))
print(f"{changed} files changed")
sys.exit(0)
