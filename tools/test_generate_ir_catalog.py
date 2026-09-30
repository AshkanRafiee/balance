"""Standard-library checks for the official Iranian catalog generator."""

import tempfile
import unittest
from pathlib import Path

import generate_ir_catalog as generator

REPOSITORY = Path(__file__).resolve().parents[1]
LEGACY = REPOSITORY / "parser-core/src/main/java/com/ashkanrafiee/balance/parser/legacy/LegacyBankRules.java"
COMMITTED = REPOSITORY / "rules/app/ir-official/catalog.json"


class GeneratorTest(unittest.TestCase):
    def render(self, path):
        rows = generator.parse_legacy(path)
        return generator.document(*generator.parse_and_merge(path)), generator.write

    def test_goldens(self):
        document, _ = self.render(LEGACY)
        self.assertEqual(document["counts"]["banks"], generator.GOLDEN_BANKS)
        self.assertEqual(document["counts"]["aliases"], generator.GOLDEN_ALIASES)
        self.assertEqual(len(document["banks"]), generator.GOLDEN_BANKS)
        self.assertEqual(len({bank["id"] for bank in document["banks"]}),
                         len(document["banks"]))
        self.assertEqual(document["allowlist"], generator.ALLOWLIST)

    def test_ids_are_slugs(self):
        document, _ = self.render(LEGACY)
        for bank in document["banks"]:
            self.assertEqual(generator.bank_id(bank["name"]), bank["id"])

    def test_deterministic_rendering(self):
        document, write = self.render(LEGACY)
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "one.json"
            second = Path(directory) / "two.json"
            write(document, first)
            write(document, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_committed_document_is_generated(self):
        document, write = self.render(LEGACY)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            write(document, path)
            self.assertEqual(path.read_bytes(), COMMITTED.read_bytes())

    def test_normalize_matches_legacy_probes(self):
        self.assertEqual(generator.normalize("+98 3000 5816"), "30005816")
        self.assertEqual(generator.normalize("۰۰۹۸۳۲۳۱۴۱۴۱"), "32314141")
        self.assertEqual(generator.normalize("b.pasargad"), "bpasargad")
        self.assertEqual(generator.normalize("صادرات"), "صادرات")


if __name__ == "__main__":
    unittest.main()