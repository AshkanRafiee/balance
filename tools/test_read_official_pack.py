"""Standard-library checks for the official IR pack frontend and its catalog gate."""

import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import read_official_pack as reader

REPOSITORY = Path(__file__).resolve().parent.parent


def write_document(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")


def minimal_tree(root, overrides=None):
    """A valid-ish official IR tree the python frontend fully accepts."""
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    catalog = {
        "engine": "prototype-1",
        "banks": [{"id": "ir.synthetic", "name": "Synthetic", "senders": ["SynthBank", "12345"]}],
    }
    write_document(root / "catalog.json", catalog)
    pack = {
        "schema": "prototype-1",
        "id": "official.ir.synthetic",
        "revision": "t1",
        "bank": {"id": "ir.synthetic", "country": "IR", "name": "Synthetic", "provenance": "OFFICIAL"},
        "templates": [{"id": "t", "senders": ["SynthBank"], "guards": [], "outputs": [
            {"id": "out", "region": {"line": -1, "after": "", "before": "", "maxLength": 1024},
             "kind": "BOOKED_BALANCE",
             "money": {"amount": {"line": 0, "after": "", "before": "", "maxLength": 32},
                       "currency": {"fixed": "IRR"}, "decimal": ".", "group": ",",
                       "grouping": "WESTERN", "digits": "ASCII", "unitMultiplier": 1}}]},
        ],
    }
    if overrides:
        pack = {**pack, **overrides.get("pack", {})}
    bank_dir = root / "ir.synthetic"
    bank_dir.mkdir(parents=True)
    write_document(bank_dir / "pack.json", pack)
    cases = [{
        "id": "one", "sourceId": "x", "sender": "SynthBank", "arrival": "2026-01-01T00:00:00Z",
        "zone": "UTC", "body": "x",
        "legacy": {"status": "PARSED", "bank": "ir.synthetic", "account": None, "reason": None,
                   "channel": None, "balance": 1, "txn": -1, "time": 1, "timeArrival": True},
        "expected": {"status": "PARSED", "outputs": [
            {"id": "out", "account": None, "currency": "IRR", "minorUnits": "1", "scale": 0}]},
    }]
    cases = overrides.get("cases", cases) if overrides else cases
    write_document(bank_dir / "fixtures.json", {"schema": "prototype-fixtures-1",
                                                "pack": "official.ir.synthetic", "cases": cases})
    return root


def run_frontend(root):
    script = Path(__file__).resolve().parent / "read_official_pack.py"
    return subprocess.run([sys.executable, "-B", str(script), str(root)],
                          capture_output=True, text=True)


class OfficialReaderTest(unittest.TestCase):
    def load(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.json"
            path.write_bytes(text if isinstance(text, bytes) else text.encode("utf-8"))
            return reader.load(path)

    def test_strict_input(self):
        for text in ('{"a":null,"a":1}', '{"a":1,"\\u0061":2}', '{"a":TRUE}',
                     '{"a":"\\q"}', '{"a":NaN}', '{"a":1;}', '{}{}', '[]',
                     '{"a":"\n"}', '{"a":"\\ud800"}', '\ufeff{}', b'{"a":"\xff"}'):
            with self.subTest(kind="malformed"):
                with self.assertRaises((ValueError, UnicodeError)):
                    self.load(text)

    def test_catalog_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = minimal_tree(Path(directory))
            first = run_frontend(root)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(json.loads(first.stdout)[0]["pack"]["id"], "official.ir.synthetic")

            synthetic = root / "ir.synthetic"
            pack = json.loads((synthetic / "pack.json").read_text())
            pack["templates"][0]["senders"] = ["NotInCatalog"]
            write_document(synthetic / "pack.json", pack)
            second = run_frontend(root)
            self.assertNotEqual(second.returncode, 0)
            pack["templates"][0]["senders"] = ["SynthBank"]
            pack["bank"]["country"] = "US"
            write_document(synthetic / "pack.json", pack)
            self.assertNotEqual(run_frontend(root).returncode, 0)
            pack["bank"]["country"] = "IR"
            pack["bank"]["provenance"] = "COMMUNITY"
            write_document(synthetic / "pack.json", pack)
            self.assertNotEqual(run_frontend(root).returncode, 0)
            pack["bank"]["provenance"] = "OFFICIAL"
            pack["bank"]["id"] = "ir.other"
            write_document(synthetic / "pack.json", pack)
            self.assertNotEqual(run_frontend(root).returncode, 0)

    def test_deterministic_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root0 = minimal_tree(Path(directory))
            root1 = minimal_tree(Path(directory + "-1"))
            self.assertEqual(run_frontend(root0).stdout, run_frontend(root1).stdout)

    def test_fixture_metadata_validation(self):
        case = {"id": "one", "sourceId": "x", "sender": "x", "arrival": "x", "zone": "x",
                "body": "x", "expected": {"status": "INVALID", "outputs": []}}
        def cases(document):
            return {"schema": "prototype-fixtures-1", "pack": "x", "cases": document}
        self.assertEqual(reader.fixtures(cases([{**case, "divergentTime": True}])), cases([{**case, "divergentTime": True}]))
        with self.assertRaises(ValueError):
            reader.fixtures(cases([{**case, "unknown": 1}]))
        with self.assertRaises(ValueError):
            reader.fixtures(cases([case, case]))
        with self.assertRaises(ValueError):
            reader.fixtures(cases([{**case, "divergentTime": "yes"}]))
        with self.assertRaises(ValueError):
            reader.fixtures(cases([{**case, "legacy": {"status": "NO_MATCH", "timeArrival": True}}]))

    def test_limited_depth_and_size(self):
        with self.assertRaises(ValueError):
            self.load('{"a":' + '[' * 16 + '0' + ']' * 16 + '}')
        with self.assertRaises(ValueError):
            self.load('{"a":"' + 'x' * reader.MAX_BYTES + '"}')

    def test_published_currency_registry_matches_the_engine(self):
        """The published schema and the engine's registry must name the same currencies.

        A code the engine knows but the schema does not is an amount a contributor
        cannot express; a code the schema names but the engine does not is a pack that
        decodes to a currency the app would then draw at the wrong scale, or refuse.
        Either way the two lists are only useful together, so they are checked together.
        """
        schema = json.loads((REPOSITORY / "rules/schema/bank-pack-prototype-1.schema.json").read_text(
            encoding="utf-8"))
        published = schema["$defs"]["currencyCode"]["enum"]
        source = (REPOSITORY / "parser-core/src/main/java/com/ashkanrafiee/balance/parser/Rules.java"
                  ).read_text(encoding="utf-8")
        declared = re.search(r"enum Currency \{(.*?);", source, re.S)
        self.assertIsNotNone(declared, "Rules.Currency is no longer a one-line enum; update this check")
        engine = re.findall(r"\b([A-Z]{3})\((\d)\)", declared.group(1))
        self.assertEqual([code for code, _ in engine], published)
        # The ISO 4217 minor-unit exponents are asserted where the registry is defined, in
        # CurrencyHelperTest; here only the two name lists are compared, so this check can
        # never become a third place where a scale is written down.
        for _, scale in engine:
            self.assertIn(scale, ("0", "2", "3"))
        contract = (REPOSITORY / "parser-core/CONTRACT.md").read_text(encoding="utf-8")
        for code in published:
            self.assertIn(code, contract, f"{code} is missing from the documented registry")

    def test_every_shipped_pack_declares_its_provenance(self):
        """A pack must say who asked for it, and must be readable without saying.

        Provenance is what the app offers a switch for, so an undeclared pack would
        be one the settings screen cannot label. Decoding without it must still fail:
        a required field that quietly defaulted would let a contributed pack ship
        looking official.
        """
        schema = json.loads((REPOSITORY / "rules/schema/bank-pack-prototype-1.schema.json").read_text(
            encoding="utf-8"))
        self.assertIn("provenance", schema["properties"]["bank"]["required"])
        for pack_path in sorted((REPOSITORY / "rules/app").glob("*/*/pack.json")):
            pack = json.loads(pack_path.read_text(encoding="utf-8"))
            self.assertIn("provenance", pack["bank"], f"{pack_path} declares no provenance")
            self.assertIn(pack["bank"]["provenance"],
                          schema["properties"]["bank"]["properties"]["provenance"]["enum"])
        for pack_path in sorted((REPOSITORY / "rules/app/ir-official").glob("*/pack.json")):
            pack = json.loads(pack_path.read_text(encoding="utf-8"))
            self.assertEqual(pack["bank"]["provenance"], "OFFICIAL", f"{pack_path} is not official")


if __name__ == "__main__":
    unittest.main()