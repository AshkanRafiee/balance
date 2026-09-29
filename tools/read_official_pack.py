#!/usr/bin/env python3
"""Read trusted official Iranian packs and fixtures for the JVM build gate.

Python's JSON codec enforces hygiene (duplicate-key rejection, no BOM, strict
UTF-8, bounded size/depth/nodes/tokens, strict integer/float parsing) before
Groovy decodes the canonical result. This frontend additionally cross-checks
every official IR pack against the committed catalog: the pack must belong to a
catalog bank and every template sender must be an exact catalog sender for that
bank. Java PackDocument remains the semantic validator. Output is captured by
Gradle, not printed to user logs; input errors intentionally omit document
contents.
"""

import io
import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

MAX_BYTES = 256 * 1024
MAX_DEPTH = 16
MAX_NODES = 65_536
MAX_TOKENS = 65_536


def fail():
    raise ValueError("Invalid official pack document")


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail()
        result[key] = value
    return result


def integer(text):
    if len(text) > 256:
        fail()
    try:
        mantissa, _, exponent = text.lower().partition("e")
        exponent = int(exponent or "0")
        fraction_length = len(mantissa.partition(".")[2])
        scale = fraction_length - exponent
        if abs(exponent) > 2**31 - 1 or not -(2**31) <= scale <= 2**31 - 1:
            fail()
        value = Decimal(text)
        if not value.is_finite() or value < -(2**63) or value > 2**63 - 1:
            fail()
        if value != value.to_integral_value():
            fail()
        return int(value)
    except (InvalidOperation, OverflowError):
        fail()


def reject_constant(_):
    fail()


def validate_tree(value, depth=0, remaining=None):
    if remaining is None:
        remaining = [MAX_NODES]
    remaining[0] -= 1
    if remaining[0] < 0:
        fail()
    if isinstance(value, str):
        if any(0xD800 <= ord(c) <= 0xDFFF for c in value):
            fail()
    elif isinstance(value, (dict, list)):
        if depth >= MAX_DEPTH:
            fail()
        if isinstance(value, dict):
            for key, child in value.items():
                validate_tree(key, depth + 1, remaining)
                validate_tree(child, depth + 1, remaining)
        else:
            for child in value:
                validate_tree(child, depth + 1, remaining)


def lexical_tokens(value):
    if isinstance(value, dict):
        return 2 + 2 * len(value) + max(0, len(value) - 1) + sum(
            lexical_tokens(child) for child in value.values())
    if isinstance(value, list):
        return 2 + max(0, len(value) - 1) + sum(lexical_tokens(child) for child in value)
    return 1


def load(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        fail()
    text = data.decode("utf-8", errors="strict")
    value = json.loads(text, object_pairs_hook=object_pairs, parse_int=integer,
                       parse_float=integer, parse_constant=reject_constant)
    if not isinstance(value, dict):
        fail()
    validate_tree(value)
    if lexical_tokens(value) > MAX_TOKENS:
        fail()
    return value


def expected_output(value):
    required = {"id", "account", "currency", "minorUnits", "scale"}
    if not isinstance(value, dict) or not required.issubset(value) or set(value) - required - {"time", "reason", "channel"}:
        fail()
    for key in ("id", "currency", "minorUnits"):
        if not isinstance(value[key], str) or not value[key]:
            fail()
    if value["account"] is not None and (not isinstance(value["account"], str) or not value["account"]):
        fail()
    if type(value["scale"]) is not int:
        fail()
    if "time" in value:
        time = value["time"]
        if not isinstance(time, dict) or set(time) != {"instant", "precision", "zone", "fallback"}:
            fail()
        if any(not isinstance(time[key], str) or not time[key] for key in ("instant", "precision", "zone")):
            fail()
        if time["precision"] not in {"DAY", "MINUTE", "SECOND", "ARRIVAL"} or type(time["fallback"]) is not bool:
            fail()
    for key in ("reason", "channel"):
        text = value.get(key)
        if text is not None and (not isinstance(text, dict) or set(text) != {"id", "text"}
                or any(not isinstance(v, str) or not v for v in text.values())):
            fail()
    return value


def fixtures(value):
    if set(value) != {"schema", "pack", "cases"} or value["schema"] != "prototype-fixtures-1":
        fail()
    cases = value["cases"]
    if not isinstance(cases, list) or not cases or len(cases) > 256:
        fail()
    ids = set()
    for case in cases:
        allowed = {"id", "sourceId", "sender", "arrival", "zone", "body", "expected",
                   "legacy", "divergentTime"}
        if not isinstance(case, dict) or not allowed.issuperset(case) or not {"id", "sourceId", "sender",
                "arrival", "zone", "body", "expected"}.issubset(case):
            fail()
        for key in ("id", "sourceId", "sender", "arrival", "zone", "body"):
            if not isinstance(case[key], str) or not case[key]:
                fail()
        if case["id"] in ids:
            fail()
        ids.add(case["id"])
        expected = case["expected"]
        if not isinstance(expected, dict) or set(expected) != {"status", "outputs"}:
            fail()
        if expected["status"] not in {
                "PARSED", "UNKNOWN_SENDER", "NO_MATCH", "ABSENT", "INVALID", "OVERFLOW",
                "AMBIGUOUS", "LIMIT_EXCEEDED"}:
            fail()
        outputs = expected["outputs"]
        if not isinstance(outputs, list) or (bool(outputs) != (expected["status"] == "PARSED")):
            fail()
        output_ids = set()
        for output in outputs:
            expected_output(output)
            if output["id"] in output_ids:
                fail()
            output_ids.add(output["id"])
        if "divergentTime" in case and type(case["divergentTime"]) is not bool:
            fail()
        if "legacy" in case:
            legacy = case["legacy"]
            required = {"status", "bank", "txn", "balance", "account", "reason", "channel",
                        "time", "timeArrival"}
            if not isinstance(legacy, dict) or set(legacy) != required:
                fail()
            legal = {"PARSED", "UNKNOWN_SENDER", "NO_MATCH"}
            if not isinstance(legacy["status"], str) or legacy["status"] not in legal:
                fail()
            if legacy["bank"] is not None and not isinstance(legacy["bank"], str):
                fail()
            for key in ("account", "reason", "channel"):
                if legacy[key] is not None and not isinstance(legacy[key], str):
                    fail()
            for key in ("txn", "balance"):
                if legacy[key] is not None and type(legacy[key]) is not int:
                    fail()
            if type(legacy["time"]) is not int or type(legacy["timeArrival"]) is not bool:
                fail()
    return value


def catalog(path):
    value = load(path / "catalog.json")
    if value.get("engine") != "prototype-1" or "banks" not in value:
        fail()
    banks = {}
    for bank in value["banks"]:
        if not isinstance(bank, dict) or not {"id", "senders"}.issubset(bank):
            fail()
        senders = bank["senders"]
        if not isinstance(senders, list) or not all(isinstance(s, str) for s in senders):
            fail()
        banks[bank["id"]] = senders
    return banks


def main():
    if len(sys.argv) != 2:
        raise ValueError("Expected the official IR rules directory")
    root = Path(sys.argv[1])
    banks = catalog(root)
    if not banks:
        raise ValueError("No catalog banks")
    documents = []
    for path in sorted(root.glob("*/pack.json")):
        pack = load(path)
        if not isinstance(pack, dict) or pack.get("schema") != "prototype-1":
            fail()
        bank = pack.get("bank")
        if not isinstance(bank, dict) or not {"id", "country", "name", "provenance"}.issubset(bank):
            fail()
        # This tree is the Iranian market we ship coverage for on our own account, so a pack here
        # claiming to be community-contributed is a mistake in the provenance itself, not a
        # judgement about the pack: the two are independent, and neither may be inferred.
        if bank["country"] != "IR" or bank["id"] not in banks or bank["provenance"] != "OFFICIAL":
            fail()
        templates = pack.get("templates")
        if not isinstance(templates, list) or not templates:
            fail()
        for template in templates:
            senders = template.get("senders")
            if not isinstance(senders, list) or not senders:
                fail()
            known = set(banks[bank["id"]])
            for sender in senders:
                if not isinstance(sender, str) or sender not in known:
                    fail()
        fs = fixtures(load(path.with_name("fixtures.json")))
        if fs["pack"] != pack["id"]:
            fail()
        documents.append({"pack": pack, "fixtures": fs})
    if not documents:
        raise ValueError("No official packs")
    json.dump(documents, sys.stdout, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, RecursionError, InvalidOperation):
        print("Official pack input rejected", file=sys.stderr)
        sys.exit(1)