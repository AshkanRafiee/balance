"""Standard-library checks for the repository-only fixture frontend."""

import copy
import json
import tempfile
import unittest
from pathlib import Path

import read_rule_examples as reader


class ExampleReaderTest(unittest.TestCase):
    def load(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.json"
            path.write_bytes(text if isinstance(text, bytes) else text.encode("utf-8"))
            return reader.load(path)

    def test_strict_input(self):
        for text in ('{"a":null,"a":1}', '{"a":1,"\\u0061":2}', '{"a":TRUE}',
                     '{"a":"\\q"}', '{"a":1.}', '{"a":NaN}', '{"a":1;"b":2}',
                     '{"a":1,}', '{}{}', '[]', '{"a":"\n"}', '{"a":"\\ud800"}',
                     '{"a":/*comment*/1}', b'{"a":"\xff"}', '\ufeff{}'):
            with self.subTest(kind="malformed"):
                with self.assertRaises((ValueError, UnicodeError)):
                    self.load(text)

    def test_exact_counter_numbers(self):
        self.assertEqual(self.load('{"a":1.00,"b":1e2,"c":-0}'), {"a": 1, "b": 100, "c": 0})
        for text in ('0e2147483648', '0e-2147483648', '0.0e-2147483647',
                     '1e99999999', '0.5', '9' * 257):
            with self.subTest(kind="unrepresentable"):
                with self.assertRaises(ValueError):
                    self.load('{"a":' + text + '}')
        self.assertEqual(self.load('{"a":0e2147483647,"b":0e-2147483647}'), {"a": 0, "b": 0})

    def test_limits_and_unicode(self):
        self.assertEqual(self.load('{"a":"\\ud83d\\ude00"}'), {"a": "😀"})
        self.assertEqual(reader.lexical_tokens({"a": [1, {"b": True}]}), 13)
        with self.assertRaises(ValueError):
            self.load('{"a":' + '[' * 16 + '0' + ']' * 16 + '}')
        with self.assertRaises(ValueError):
            self.load('{"a":"' + 'x' * reader.MAX_BYTES + '"}')
        # Fewer than MAX_NODES tree nodes but more than MAX_TOKENS JSON tokens.
        with self.assertRaises(ValueError):
            self.load(json.dumps({"a": [[0] for _ in range(17_000)]}))

    def test_empty_and_duplicate_fixtures_rejected(self):
        with self.assertRaises(ValueError):
            reader.fixtures({"schema": "prototype-fixtures-1", "pack": "x", "cases": []})
        case = {"id": "one", "sourceId": "x", "sender": "x", "arrival": "x", "zone": "x",
                "body": "x", "expected": {"status": "INVALID", "outputs": []}}
        with self.assertRaises(ValueError):
            reader.fixtures({"schema": "prototype-fixtures-1", "pack": "x", "cases": [case, case]})

    def test_shared_frontend_acceptance_corpus(self):
        path = Path(__file__).resolve().parents[1] / "rules/examples/json-acceptance/cases.json"
        corpus = reader.load(path)
        self.assertEqual(corpus["schema"], "prototype-json-acceptance-1")
        self.assertEqual(len(corpus["cases"]), 35)
        ids = set()
        for case in corpus["cases"]:
            with self.subTest(case=case["id"]):
                self.assertNotIn(case["id"], ids)
                ids.add(case["id"])
                data = bytes.fromhex(case["bytesHex"]) if "bytesHex" in case else case["text"]
                try:
                    self.load(data)
                    accepted = True
                except (ValueError, UnicodeError):
                    accepted = False
                self.assertEqual(accepted, case["accepted"])

    def test_optional_output_expectations_are_typed(self):
        output = {"id": "one", "account": None, "currency": "IRR", "minorUnits": "100", "scale": 0,
                  "reason": None, "channel": {"id": "atm", "text": "ATM"},
                  "time": {"instant": "2026-04-04T12:00:00Z", "precision": "ARRIVAL",
                           "zone": "UTC", "fallback": True}}
        document = {"schema": "prototype-fixtures-1", "pack": "synthetic", "cases": [{
            "id": "one", "sourceId": "x", "sender": "x", "arrival": "x", "zone": "x", "body": "x",
            "expected": {"status": "PARSED", "outputs": [output]}}]}
        self.assertIs(reader.fixtures(document), document)
        for key, value in (("account", 123), ("reason", {"id": "x"}),
                           ("channel", {"id": "atm", "text": 123}), ("scale", True),
                           ("time", {"instant": "x", "precision": "GUESSED", "zone": "UTC", "fallback": True}),
                           ("time", {"instant": "x", "precision": "DAY", "zone": "UTC", "fallback": "false"})):
            bad = copy.deepcopy(document)
            bad["cases"][0]["expected"]["outputs"][0][key] = value
            with self.subTest(field=key):
                with self.assertRaises(ValueError):
                    reader.fixtures(bad)


if __name__ == "__main__":
    unittest.main()
