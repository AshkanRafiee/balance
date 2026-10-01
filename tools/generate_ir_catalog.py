#!/usr/bin/env python3
"""Generate the official Iranian sender catalog from the frozen legacy tables.

No third-party modules. The RULES/OFFICIAL_EXTRA_RULES tables are read straight
from the frozen Java contract so the bundled document cannot drift by
transcription, and generation fails loudly if the frozen data ever changes its
known cardinalities. Input errors intentionally omit sender contents. This tool
only authors the bundled document; identity and behavior are validated
independently by the JVM catalog pipeline at build time.
"""

import json
import re
import sys
from pathlib import Path

MARKET = "IR"
ENGINE = "prototype-1"
PROFILE = "ir-legacy-s1"
SCHEMA = "sender-catalog-1"
SOURCE = "parser-core LegacyBankRules RULES/OFFICIAL_EXTRA_RULES (frozen)"

ALLOWLIST = {
    "20004860": "ir.middle-east",
    "30005816": "ir.tosee-taavon",
    "98700717": "ir.melli",
}
# What each shipped Iranian rule was built from, and who has confirmed it. Kept here because the
# catalog is generated: a review fact that lived only in the JSON would be lost on the next
# regeneration. 'realMessages' means the rule has been checked against messages the bank actually
# sends; 'marketReviewer' means someone who holds an account at that bank has confirmed the rule
# reads their own messages. The two are separate, and the plan is honest about the difference:
# technical gates can only prove the rule does what its fixtures say.
REVIEW_EVIDENCE = "legacy-tables"
REVIEW_REAL_MESSAGES = True
REVIEW_MARKET_REVIEWER = True
REVIEWED_ON = "2026-09-30"

GOLDEN_BANKS = 43
GOLDEN_ALIASES = 345
GOLDEN_REACHABLE = 42
GOLDEN_UNREACHABLE = {"ir.tosee-credit-inst"}


def bank_id(name):
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return "ir." + slug


def fold_digit(character):
    code = ord(character)
    if 0x06F0 <= code <= 0x06F9 or 0x0660 <= code <= 0x0669:
        return chr(0x30 + code - (0x06F0 if code >= 0x06F0 else 0x0660))
    return character


def normalize(raw):
    out = "".join(character.lower() for character in (fold_digit(c) for c in raw)
                  if character.isalnum())
    if out.startswith("0098"):
        out = out[4:]
    if out.startswith("98") and len(out) > 8:
        out = out[2:]
    return out


def java_split(aliases):
    parts = aliases.split("|")
    while parts and not parts[-1]:
        parts.pop()
    return parts


ROW = re.compile(r'\{\s*"([^"]*)"\s*,\s*"([^"]*)"(?:,\s*"([^"]*)")?\s*\}', re.S)


def parse_legacy(source):
    text = Path(source).read_text(encoding="utf-8")
    rows = []
    for array_name in ("RULES", "OFFICIAL_EXTRA_RULES"):
        block = re.search(r"String\[\]\[\] %s = \{(.*?)\n\s*\};" % array_name, text, re.S)
        if block is None:
            raise ValueError("Missing legacy table " + array_name)
        for match in ROW.finditer(block.group(1)):
            rows.append((match.group(1), java_split(match.group(2)),
                         {"J": "jalali"}.get(match.group(3), match.group(3))))
    return rows


def merge(rows):
    merged = {}
    for name, aliases, calendar in rows:
        if name not in merged:
            merged[name] = {"aliases": [], "calendar": calendar}
        merged[name]["aliases"].extend(aliases)
    return merged


def compiled_tables(rows):
    """Exact Java mirror: rules in declaration order, incrementing global index,
    first-wins EXACT registration and first-wins suffix-of registration."""
    exact = {}
    suffix_of = {}
    for index, (name, aliases, _) in enumerate(rows):
        for raw in aliases:
            key = normalize(raw)
            if key and key not in exact:
                exact[key] = (name, index)
                if key.isdigit() and len(key) >= 5:
                    for length in range(5, len(key) + 1):
                        suffix_of.setdefault(key[-length:], (name, index))
    return exact, suffix_of


def resolve_row(rows, sender):
    exact, suffix_of = compiled_tables(rows)
    key = normalize(sender)
    if not key:
        return None
    if key.isdigit() and len(key) >= 5:
        best = suffix_of.get(key)
        start = len(key) - 5
        for length in range(5, len(key) + 1):
            direct = exact.get(key[start:start + length])
            if direct is not None and (best is None or direct[1] < best[1]):
                best = direct
            start -= 1
        return None if best is None else best[0]
    direct = exact.get(key)
    return None if direct is None else direct[0]


def derive(rows, merged):
    exact, _ = compiled_tables(rows)
    collisions = {}
    for key, (name, _) in sorted(exact.items()):
        owners = set()
        for bank, content in merged.items():
            if any(normalize(raw) == key for raw in content["aliases"]):
                owners.add(bank)
        if len(owners) > 1 and key not in collisions:
            collisions[key] = name
    reachable = {resolve_row(rows, raw) for content in merged.values() for raw in content["aliases"]}
    reachable.discard(None)
    return collisions, reachable


def document(rows, merged):
    collisions, reachable = derive(rows, merged)
    all_aliases = [raw for content in merged.values() for raw in content["aliases"]]
    if len(merged) != GOLDEN_BANKS:
        raise ValueError("Frozen legacy bank count changed")
    if len(all_aliases) != GOLDEN_ALIASES:
        raise ValueError("Frozen legacy alias count changed")
    if len(reachable) != GOLDEN_REACHABLE:
        raise ValueError("Frozen legacy reachable-bank count changed")
    if {bank_id(name) for name in reachable} & GOLDEN_UNREACHABLE:
        raise ValueError("Frozen legacy shadowing contract changed")
    winners = {key: bank_id(owner) for key, owner in sorted(collisions.items())}
    if winners != ALLOWLIST:
        raise ValueError("Frozen legacy collision allowlist changed")
    banks = []
    for name, content in merged.items():
        banks.append({
            "id": bank_id(name),
            "name": name,
            "calendar": content["calendar"],
            "senders": list(content["aliases"]),
            "review": {
                "evidence": REVIEW_EVIDENCE,
                "realMessages": REVIEW_REAL_MESSAGES,
                "marketReviewer": REVIEW_MARKET_REVIEWER,
                "reviewedOn": REVIEWED_ON,
            },
        })
    return {
        "catalog": SCHEMA,
        "market": MARKET,
        "engine": ENGINE,
        "profile": PROFILE,
        "source": SOURCE,
        "counts": {"banks": len(merged), "aliases": len(all_aliases)},
        "allowlist": dict(sorted(ALLOWLIST.items())),
        "banks": banks,
    }


def write(document, output):
    payload = json.dumps(document, sort_keys=True, indent=2,
                         ensure_ascii=False, allow_nan=False)
    Path(output).write_text(payload + "\n", encoding="utf-8")


def main():
    if len(sys.argv) != 3:
        raise ValueError("Expected the legacy source and catalog output paths")
    try:
        write(document(*parse_and_merge(sys.argv[1])), sys.argv[2])
    except (ValueError, OSError):
        print("Official catalog generation rejected", file=sys.stderr)
        sys.exit(1)


def parse_and_merge(path):
    rows = parse_legacy(path)
    return rows, merge(rows)


if __name__ == "__main__":
    main()