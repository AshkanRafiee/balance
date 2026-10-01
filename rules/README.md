# Portable rules

A pack is one bank's reading rules as a single JSON file. `rules/app/**` is the
asset root `app/build.gradle` adds to the APK, so those packs **are loaded by the
app** — `EngineRules` reads every `rules/app/<region>/<bank-id>/pack.json` and
answers from them — and `rules/examples/**` is the asset root the instrumented
tests read the examples below from. The examples stay synthetic: no real bank
support is claimed by them.

Every pack, shipped or written on the device, declares `"schema": "prototype-1"`.

- [Pack schema](schema/bank-pack-prototype-1.schema.json)
- [Two-currency example](examples/multi-currency/pack.json)
- [Synthetic expected outputs](examples/multi-currency/fixtures.json)
- [Synthetic Iranian-style pack](examples/iranian-prototype/pack.json) and
  [fixtures](examples/iranian-prototype/fixtures.json)
- [Synthetic international pack](examples/international/pack.json) and
  [fixtures](examples/international/fixtures.json)
- [Raw JSON acceptance corpus](examples/json-acceptance/cases.json)
- [Executable core contract](../parser-core/CONTRACT.md)

## Reading the example

One message declares two required outputs, `usd-balance` and `eur-balance`, for
the same account. Each output selects its own line, validates the printed
currency, and extracts only that line's balance. `100.00 USD` becomes `10000`
integer minor units, whereas `200.00 EUR` becomes `20000`. They are separate
ledgers, never one sum. An invalid second balance rejects the whole candidate.

Each bank may contain several templates; each template may have several outputs.
Country is descriptive metadata, not a currency, sender-prefix or timezone
default. No field silently inherits parsing behavior from the device language.
Legacy Gregorian dates use the explicit message timezone. Extended date rules
pin their own timezone in `date.options`; `calendar` stays on `date`. All seven
options members are required when that object is present. Jalali dates require
options. No timezone or year base is inferred from country metadata.

The Iranian-style pack is entirely synthetic, with invented sender IDs, account
references and messages. Its Mellat-style glued account/short Jalali date,
Melli-style compact date, Blu-style reason, Tejarat-style channel and dotted
reference demonstrate primitives, not verified bank coverage or official formats.
Fixtures pin arrival instants and zones, including a neighboring-year rollover,
invalid/missing/stale date fallback, unknown optional metadata, unresolved
accounts and rejection of all outputs when either required amount is invalid.

## Codec and semantic validation

The schema documents the typed Java boundary; it does not replace the constructors
and compiled-snapshot checks in `Rules`/`Parser`. Those checks additionally enforce
unique template/output IDs, pack revision consistency, distinct separators, IRR-only
toman conversion, positive guards, field limits and other semantic relationships.
Map `group: ""` to the internal disabled separator, never to a literal NUL in JSON.
Optional absent properties map to absent typed values, not implicit heuristics.
Field normalization, numeric selection and line termination are opt-in. Region,
amount (including original amount) and date selectors remain raw: normalization
is omitted or `NONE`, and numeric selection is forbidden. Amount/date rules use
their own `digits` policy. Currency/direction tokens permit only `NONE` or `TEXT`
normalization and no numeric selection. `sign` defaults to `LEADING`; `TRAILING`
declares the sign after the digits. `leadingPoint` defaults to false, and true is
the only widening of the money contract: it reads a bank that prints amounts below
one unit without an integer part (`.11`) as zero point one one. It is opt-in per
money rule because a missing integer part is otherwise the loudest sign that a
capture is misaligned, and a capture that lost its integer digits must keep
failing rather than quietly become a fraction. Accounts and reason/channel selectors
retain the full set of transforms. `accountOptional` defaults to false; only true permits an
omitted account selector or unresolved reference. Optional properties must be
omitted rather than set to JSON null in packs. Reason/channel rules map finite
tokens to semantic IDs and omit unknown or ambiguous tokens. Repeated optional
anchors omit the metadata with `CAPTURE_AMBIGUOUS` while retaining valid financial
outputs; account ambiguity still rejects the candidate. Date options support explicit
full/two-digit years or bounded neighboring-year inference, never an inferred
date order. The core also validates zone IDs, numeric segment-width sums and
minimum/maximum relationships that JSON Schema cannot compare directly.

`./gradlew :parser-core:check` executes these JSON examples through the Python
standard-library frontend, Gradle's canonical JSON reader, shared Java
`PackDocument` mapper and parser. Python 3 is a build-tool prerequisite; it adds
nothing to the app. The same files are Android test assets for the prototype
platform-reader adapter. A shared raw JSON acceptance corpus includes duplicate
keys, malformed UTF-8/grammar and number/Unicode edge cases.

Production codec acceptance remains gated on Android execution and resource
measurements. Duplicate names are rejected before map insertion; unknown keys
and semantic limits are rejected by the shared mapper. A small lexical preflight
compensates for Android `JsonReader`'s known non-lenient-mode exceptions; platform
and standard-library parsers still own structural parsing. No general-purpose
JSON parser or new third-party production dependency has been introduced.

JSON Schema measures strings as Unicode code points; the Java prototype also
enforces UTF-16 code-unit limits and rejects malformed surrogates/NUL/bare CR.
Schema acceptance alone is therefore insufficient. Country codes also need a
registry check beyond the two-letter structural constraint.

Fixture monetary integers are decimal strings to avoid precision loss in runtimes
whose JSON numbers are floating point. Expected outputs are compared by stable
output ID, not declaration order. Build tooling validates fixture shape,
nonempty/unique cases and baseline coverage. Platform execution and broader
semantic fixtures remain gates before claiming complete cross-codec conformance.

Iranian fixtures additionally specify optional assertions: `time` mirrors
`Fact.time()` with `instant`, `precision`, `zone` and `fallback`; `reason` and
`channel` mirror their accessors as `{ "id": "...", "text": "..." }` or null.
Here `text` is the normalized captured token. Omitted assertion keys make no
claim about that metadata; explicit null asserts its absence. A fixture account
may be null when unresolved. The fixture runner compares these optional assertions
alongside the five required base fields.

## Review record

Every catalog bank carries a `review` record, and the build rejects a catalog
without one:

- `evidence` — what the rule was built from: `legacy-tables`, `reported-messages`
  or `official-spec`.
- `realMessages` — the rule has been checked against messages that bank actually
  sends, rather than only against synthetic text derived from them.
- `marketReviewer` — someone who holds an account at that bank has confirmed the
  rule reads their own messages. `false` is a normal, honest state: a foreign
  pack backed only by reader reports has one.
- `reviewedOn` — the date of that review.

The two flags are separate on purpose, and neither is the same claim as the CI
gates passing. A fixture proves a rule does what the fixture says; the fixture is
evidence about the rule, not about the bank. A pack claiming `marketReviewer`
without `realMessages` is rejected, because a named reviewer is only meaningful
if real messages were seen. The current records say `legacy-tables` with both
flags set for the Iranian packs, and `reported-messages` with no market reviewer
for the Italian one, which is exactly how much is known about each.

## Shipped layout

```
rules/app/index.json                      the shipped regions, in the order they register
rules/app/<region>/catalog.json           that region's banks, their senders and review records
rules/app/<region>/<bank-id>/pack.json    the pack itself
rules/app/<region>/<bank-id>/fixtures.json
```

Two regions cannot both put a catalog at the asset root, so the catalogs live one
level down and the index at the root lists the regions instead. `EngineRules`
reads that index, then loads each region's `catalog.json` and every bank pack
under it, in catalog order — the order the sender index registers them in, so
registration never depends on map iteration order.

A region is a grouping for the settings screen and nothing more. Nothing here
treats an official pack as more trusted than a community one: each pack states
its own country and where it came from, and both are read the same way. Two
regions ship today: `ir-official`, with 43 Iranian banks, and `it-community`,
with one bank a reader contributed after reporting its messages.

Adding a region is a new directory under `rules/app` plus its name in
`index.json`, and nothing else.

Two rules govern what a new pack may claim:

- A bank id is unique across every region. Two regions shipping one id fail the
  load instead of letting the sender index and the id lookup disagree about which
  pack is which.
- A sender two regions claim belongs to the region listed first in `index.json`,
  and a sender the legacy Iranian table claims belongs to that table's bank
  however late its region is listed.

One catalog entry, `ir.tosee-credit-inst`, ships its files but is not loaded: the
legacy unpacked tables already own that bank, so its messages are read once.

## Packs on the device

A pack you write in the app or bring in as a file is stored on the device, in
app-private no-backup storage, so it never leaves the phone with a backup and
never travels with one either. It is composed with the shipped packs under one
rule: bundled packs register first, so an imported pack never takes a sender a
shipped pack already claims, and among local packs the first in id order wins. A
pack's stated origin only labels it — it decides what the settings screen calls
it, never whether it is loaded or how well it parses.
