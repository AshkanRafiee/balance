#!/usr/bin/env python3
"""Read the official Iranian sender catalog for the JVM runner.

No third-party modules. Like the fixture frontend this is build tooling, not an
on-device rule importer; Java keeps the compiled-snapshot validation and the
effective-digest parity gate. Output is captured by Gradle; input errors
intentionally omit document contents.
"""

import json
import sys
from pathlib import Path

MAX_BYTES = 256 * 1024
MAX_DEPTH = 16
MAX_NODES = 65536
MAX_TOKENS = 65536


def fail():
    raise ValueError("Invalid official catalog")


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail()
        result[key] = value
    return result


def integer(text):
    try:
        value = int(text, 10)
    except ValueError:
        fail()
    return value


def validate_tree(value, depth=0, remaining=None):
    if remaining is None:
        remaining = [MAX_NODES]
    remaining[0] -= 1
    if remaining[0] < 0:
        fail()
    if isinstance(value, str):
        if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
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
                       parse_float=integer, parse_constant=lambda _: fail())
    if not isinstance(value, dict):
        fail()
    validate_tree(value)
    if lexical_tokens(value) > MAX_TOKENS:
        fail()
    return value


def catalog(value):
    if set(value) != {"catalog", "market", "engine", "profile", "source",
                      "counts", "allowlist", "banks"}:
        fail()
    if value["catalog"] != "sender-catalog-1" or value["market"] != "IR" \
            or value["engine"] != "prototype-1" or value["profile"] != "ir-legacy-s1":
        fail()
    if not isinstance(value["source"], str) or not value["source"]:
        fail()
    counts = value["counts"]
    if not isinstance(counts, dict) or set(counts) != {"banks", "aliases"}:
        fail()
    allowlist = value["allowlist"]
    if not isinstance(allowlist, dict) or not allowlist:
        fail()
    for key, winner in allowlist.items():
        if (not isinstance(key, str) or not key or not isinstance(winner, str)
                or not winner):
            fail()
    banks = value["banks"]
    if not isinstance(banks, list) or not banks or len(banks) != counts["banks"]:
        fail()
    ids = set()
    names = set()
    aliases = 0
    for bank in banks:
        if not isinstance(bank, dict) or set(bank) != {"id", "name", "calendar", "senders"}:
            fail()
        if not isinstance(bank["id"], str) or not isinstance(bank["name"], str) \
                or not isinstance(bank["calendar"], str) or not bank["id"] \
                or not bank["name"] or not bank["calendar"]:
            fail()
        senders = bank["senders"]
        if not isinstance(senders, list) or not senders or len(senders) > 128:
            fail()
        for sender in senders:
            if not isinstance(sender, str) or not sender:
                fail()
        if bank["id"] in ids or bank["name"] in names:
            fail()
        ids.add(bank["id"])
        names.add(bank["name"])
        aliases += len(senders)
    if aliases != counts["aliases"]:
        fail()
    return value


def main():
    if len(sys.argv) != 2:
        raise ValueError("Expected the official catalog path")
    try:
        json.dump(catalog(load(Path(sys.argv[1]))), sys.stdout, ensure_ascii=True,
                  separators=(",", ":"), allow_nan=False)
    except (ValueError, OSError, UnicodeError, RecursionError):
        print("Official catalog reading rejected", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()