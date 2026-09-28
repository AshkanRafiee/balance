# Portable rules — prototype format

These documents explore the format for future bundled and local rules. They are
**not loaded by the app**, and `prototype-1` is not a stable contribution/import
API. No real bank support is claimed by the synthetic examples.

- [Draft schema](schema/bank-pack-prototype-1.schema.json)
- [Two-currency example](examples/multi-currency/pack.json)
- [Synthetic expected outputs](examples/multi-currency/fixtures.json)
- [Synthetic Iranian-style pack](examples/iranian-prototype/pack.json) and
  [fixtures](examples/iranian-prototype/fixtures.json)
- [Executable core contract](../parser-core/CONTRACT.md)
- [Iranian compatibility inventory](../docs/parser-compatibility-inventory.md)

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
normalization and no numeric selection. Accounts and reason/channel selectors
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

## Planned publication layout

Reviewed packs will live under `official/<COUNTRY>/<bank-id>/` or
`community/<COUNTRY>/<bank-id>/`, with positive and negative synthetic fixtures.
Maintenance source is assigned by the bundled catalog, not trusted from a field
inside an imported document. Promotion must preserve stable IDs. Community packs
will be opt-in. Local imports get private identities and cannot replace bundled
authority merely by copying its IDs.

The next design checkpoint must resolve Iranian primitive/calendar compatibility,
the strict codec, stable occurrence/output lineage, and measured storage budgets.
Do not add production packs until those contracts are validated.
