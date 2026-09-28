#!/usr/bin/env python3
"""Read trusted repository fixtures for the JVM runner, using Python's JSON codec.

No third-party modules. This is build tooling, not an on-device rule importer.
Java PackDocument remains the semantic validator. Output is captured by Gradle,
not printed to user logs; input errors intentionally omit document contents.
"""

import json
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

MAX_BYTES = 256 * 1024
MAX_DEPTH = 16
MAX_NODES = 65_536
MAX_TOKENS = 65_536


def fail():
    raise ValueError("Invalid example document")


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail()
        result[key] = value
    return result


def integer(text):
    # All numeric properties in this prototype pack/fixture schema are bounded
    # counters. Financial integers and timestamps are represented as strings.
    if len(text) > 256:
        fail()
    try:
        mantissa, _, exponent = text.lower().partition("e")
        exponent = int(exponent or "0")
        fraction_length = len(mantissa.partition(".")[2])
        scale = fraction_length - exponent
        # Android's BigDecimal rejects exponent overflow before interpreting zero.
        if abs(exponent) > 2**31 - 1 or not -(2**31) <= scale <= 2**31 - 1:
            fail()
        value = Decimal(text)
        if not value.is_finite() or value < -(2**31) or value > 2**31 - 1:
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
        # Python combines valid JSON surrogate escape pairs into a scalar.
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
    # Count tokens in the already structurally validated tree, including names,
    # colons, commas and container delimiters, as the Android lexical guard does.
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
    # Strict UTF-8 and no BOM; the standard parser validates JSON grammar.
    text = data.decode("utf-8", errors="strict")
    value = json.loads(text, object_pairs_hook=object_pairs, parse_int=integer,
                       parse_float=integer, parse_constant=reject_constant)
    if not isinstance(value, dict):
        fail()
    validate_tree(value)
    if lexical_tokens(value) > MAX_TOKENS:
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
        if not isinstance(case, dict) or set(case) != {
                "id", "sourceId", "sender", "arrival", "zone", "body", "expected"}:
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
            required = {"id", "account", "currency", "minorUnits", "scale"}
            if not isinstance(output, dict) or not required.issubset(output) or set(output) - required - {"time", "reason", "channel"}:
                fail()
            for key in ("id", "currency", "minorUnits"):
                if not isinstance(output[key], str) or not output[key]:
                    fail()
            if output["account"] is not None and (not isinstance(output["account"], str) or not output["account"]):
                fail()
            if type(output["scale"]) is not int or output["id"] in output_ids:
                fail()
            if "time" in output:
                time = output["time"]
                if not isinstance(time, dict) or set(time) != {"instant", "precision", "zone", "fallback"}:
                    fail()
                if any(not isinstance(time[key], str) or not time[key] for key in ("instant", "precision", "zone")):
                    fail()
                if time["precision"] not in {"DAY", "MINUTE", "SECOND", "ARRIVAL"} or type(time["fallback"]) is not bool:
                    fail()
            for key in ("reason", "channel"):
                text = output.get(key)
                if text is not None and (not isinstance(text, dict) or set(text) != {"id", "text"}
                        or any(not isinstance(value, str) or not value for value in text.values())):
                    fail()
            output_ids.add(output["id"])
    if value["pack"] == "example.multi-currency" and not {
            "same-account-two-currencies", "required-second-output-invalid",
            "wrong-currency-must-not-relabel"}.issubset(ids):
        fail()
    return value


def main():
    if len(sys.argv) != 2:
        raise ValueError("Expected the repository examples directory")
    root = Path(sys.argv[1])
    documents = []
    for path in sorted(root.glob("*/pack.json")):
        documents.append({"pack": load(path), "fixtures": fixtures(load(path.with_name("fixtures.json")))})
    if not documents:
        raise ValueError("No example documents")
    json.dump(documents, sys.stdout, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, RecursionError, InvalidOperation):
        print("Rule example input rejected", file=sys.stderr)
        sys.exit(1)
