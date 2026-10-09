#!/usr/bin/env python3
"""Every locale must translate every translatable key of values/strings.xml with matching placeholders (Plan 6),
and every <plurals> with the CLDR quantities of its language and matching placeholders (Plan 10d).
Usage: check-strings.py [res-dir]   (default: the app's src/main/res)"""
import pathlib, re, sys
import xml.etree.ElementTree as ET

RES = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/res"
LOCALES = ["de", "es", "ru", "tr", "pt", "fr", "it"]
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]")
ALLOW_SAME = {  # legitimately identical in some languages (loan words, symbols); about_beta: "Beta" is the same word in en/de/es/it/pt/tr
    "about_beta",
    "tab_feed", "thread_title", "settings_relays", "setup_watcher_heading", "tip_xmr_amount", "note_tips_chip",
    "time_minutes", "time_hours", "time_days", "setup_title", "settings_monero", "tippers_title", "notif_channel_name",
    "settings_theme_system", "settings_accent_orange", "settings_accent_blue", "settings_accent_red", "settings_accent_green",
    "settings_accent_purple", "compose_title_new", "notifications_title", "setup_watcher_url", "settings_monero_watcher",
    "tab_notifications", "settings_preset_n", "search_notes", "settings_media", "tab_messages", "messages_title",
    "chat_input_hint", "profile_message", "settings_messages", "notif_dm_channel", "note_ref_label", "profile_edit_name",
}
# CLDR plural categories Android (ICU) selects for each app language; every <plurals> needs all of them.
# fr/pt `one` also covers 0; es/fr/it/pt `many` is for large round numbers ("1 000 000 de ...").
REQUIRED_QUANTITIES = {
    "en": {"one", "other"}, "de": {"one", "other"}, "tr": {"one", "other"},
    "es": {"one", "many", "other"}, "fr": {"one", "many", "other"}, "it": {"one", "many", "other"}, "pt": {"one", "many", "other"},
    "ru": {"one", "few", "many", "other"},
}


def load(path):
    root = ET.parse(path).getroot()
    out = {}
    for el in root.findall("string"):
        out[el.get("name")] = (el.text or "", el.get("translatable", "true") != "false")
    return out


def load_plurals(path):
    root = ET.parse(path).getroot()
    return {el.get("name"): {item.get("quantity"): (item.text or "") for item in el.findall("item")} for el in root.findall("plurals")}


def placeholders(text):
    return sorted(PLACEHOLDER.findall(text))


def check(res):
    """(problems, ok_lines) for the resource directory `res`."""
    problems, ok = [], []
    base_path = res / "values/strings.xml"
    base = load(base_path)
    base_plurals = load_plurals(base_path)
    translatable = {k for k, (_, t) in base.items() if t}
    for name, items in sorted(base_plurals.items()):
        lacking = sorted(REQUIRED_QUANTITIES["en"] - items.keys())
        if lacking:
            problems.append(f"en: plurals {name} lacks {'/'.join(lacking)}")
    for loc in LOCALES:
        path = res / f"values-{loc}/strings.xml"
        if not path.exists():
            problems.append(f"{loc}: missing {path}")
            continue
        loc_strings = load(path)
        loc_plurals = load_plurals(path)
        shared = {n: items for n, items in loc_plurals.items() if n in base_plurals}
        groups = (
            ("missing", sorted(translatable - loc_strings.keys())),
            ("extra", sorted(loc_strings.keys() - base.keys())),
            ("translatable=false copied", sorted(k for k in loc_strings if k in base and not base[k][1])),
            ("identical to English", sorted(k for k in translatable if k in loc_strings and loc_strings[k][0].strip() == base[k][0].strip() and len(base[k][0]) > 3 and k not in ALLOW_SAME)),
            ("placeholder mismatch", sorted(k for k in translatable if k in loc_strings and placeholders(base[k][0]) != placeholders(loc_strings[k][0]))),
            ("plurals missing", sorted(base_plurals.keys() - loc_plurals.keys())),
            ("plurals extra", sorted(loc_plurals.keys() - base_plurals.keys())),
            ("plurals quantities", sorted(f"{n} lacks {'/'.join(sorted(REQUIRED_QUANTITIES[loc] - items.keys()))}" for n, items in shared.items() if REQUIRED_QUANTITIES[loc] - items.keys())),
            ("plurals identical to English", sorted(n for n, items in shared.items() if items.get("other", "").strip() == base_plurals[n].get("other", "").strip() and len(base_plurals[n].get("other", "")) > 3 and n not in ALLOW_SAME)),
            ("plurals placeholder mismatch", sorted(n for n, items in shared.items() if any(placeholders(t) != placeholders(base_plurals[n].get("other", "")) for t in items.values()))),
        )
        for label, items in groups:
            if items:
                problems.append(f"{loc}: {label}: {', '.join(items)}")
        if not any(items for _, items in groups):
            ok.append(f"{loc}: ok ({len(loc_strings)} strings, {len(loc_plurals)} plurals)")
    return problems, ok


def main(argv):
    res = pathlib.Path(argv[1]) if len(argv) > 1 else RES
    problems, ok = check(res)
    for line in ok + problems:
        print(line)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
