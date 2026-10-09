"""Tests for check-strings.py (Plan 10d): <plurals> per CLDR quantity, in every language, with matching placeholders.
Run: python3 -m unittest discover -s scripts -p 'test_*.py' -v"""
import importlib.util
import pathlib
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("check_strings", HERE / "check-strings.py")
check_strings = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_strings)

QUANTITIES = {
    "en": ["one", "other"], "de": ["one", "other"], "tr": ["one", "other"],
    "es": ["one", "many", "other"], "fr": ["one", "many", "other"], "it": ["one", "many", "other"], "pt": ["one", "many", "other"],
    "ru": ["one", "few", "many", "other"],
}


def resources(loc, quantities=None, text=None, with_plural=True):
    items = "".join(f'<item quantity="{q}">{text or f"%1$d {loc} {q}"}</item>' for q in (quantities or QUANTITIES[loc]))
    plural = f'<plurals name="things">{items}</plurals>' if with_plural else ""
    hello = "Hello" if loc == "en" else f"Hello {loc}"
    return f'<resources><string name="hello">{hello}</string>{plural}</resources>'


def tree(overrides=None):
    root = pathlib.Path(tempfile.mkdtemp())
    for loc in QUANTITIES:
        d = root / ("values" if loc == "en" else f"values-{loc}")
        d.mkdir(parents=True)
        (d / "strings.xml").write_text((overrides or {}).get(loc) or resources(loc), encoding="utf-8")
    return root


class PluralsCheckTest(unittest.TestCase):
    def test_complete_plurals_pass(self):
        problems, ok = check_strings.check(tree())
        self.assertEqual([], problems)
        self.assertEqual(7, len(ok))

    def test_russian_without_few_is_reported(self):
        problems, _ = check_strings.check(tree({"ru": resources("ru", ["one", "many", "other"])}))
        self.assertEqual(["ru: plurals quantities: things lacks few"], problems)

    def test_a_plural_missing_in_one_language_is_reported(self):
        problems, _ = check_strings.check(tree({"de": resources("de", with_plural=False)}))
        self.assertEqual(["de: plurals missing: things"], problems)

    def test_a_quantity_without_the_placeholder_is_reported(self):
        items = '<item quantity="one">%1$d fr</item><item quantity="many">beaucoup</item><item quantity="other">%1$d fr autres</item>'
        xml = f'<resources><string name="hello">Hello fr</string><plurals name="things">{items}</plurals></resources>'
        problems, _ = check_strings.check(tree({"fr": xml}))
        self.assertEqual(["fr: plurals placeholder mismatch: things"], problems)

    def test_english_needs_one_and_other(self):
        problems, _ = check_strings.check(tree({"en": resources("en", ["other"])}))
        self.assertIn("en: plurals things lacks one", problems)

    def test_the_app_resources_pass(self):
        problems, _ = check_strings.check(check_strings.RES)
        self.assertEqual([], problems)


if __name__ == "__main__":
    unittest.main()
