# Prototype API / codec contract

## Entry points and validation

Package: `com.ashkanrafiee.balance.parser`.

- `Rules` contains immutable records and enums. Constructors validate supported
  combinations and defensively copy collections. Invalid declarations throw
  `IllegalArgumentException`; null required values throw `NullPointerException`.
- `new Parser(List<Rules.Template>)` compiles an immutable exact-sender index,
  rejects duplicate pack/template IDs and mixed revisions of one pack, and exposes
  sorted informational `senderOverlaps()`. Snapshots can be shared across threads.
- `parse(Parser.Message)` takes a caller-owned source occurrence ID, exact sender,
  body, arrival `Instant`, and explicit `ZoneId`. No clock, locale, default zone,
  network, storage, inference, or Android API is consulted.
- The result contains status, immutable facts, structured diagnostics, and matched
  provenance. Diagnostics contain rule IDs, field names, and codes, never captured
  SMS text. Facts contain sensitive account data and must not be logged.

`Template(packId, revision, bankId, id, senders, guards, outputs)` is the top-level
typed boundary. Stable IDs/revisions are 1–64 ASCII lowercase letters, digits,
dot, underscore or hyphen. A template has exact senders, at least one positive
guard, and 1–8 explicitly declared required outputs. There are no priorities,
source-tier winners, optional outputs, implicit repeated-block expansion, or
cross-template field fallback. Revision is an opaque validated identifier, not a
computed content digest. There is no enabled flag: callers supply the active set.

`Output(id, region, account, kind, money, direction, originalAmount, date)` declares
an independently scoped event. Kinds are `BOOKED_BALANCE`, `AVAILABLE_BALANCE`, and
`POSTED_MOVEMENT`. Each has exactly one required ledger amount and currency.
Movements require a direction rule. The optional original-amount declaration, if
present, is required to parse and is context on that movement, never another fact.
The date declaration is optional and has diagnostic arrival fallback.
The extended constructor adds `accountOptional`, `reason`, and `channel`; the
original constructor retains required-account behavior. A declared financial
output is always required; optional metadata does not create optional finances.

`PackDocument.decode(Map)` translates the draft document into these records and
invokes the same constructors. It rejects unknown/missing properties, incorrect
types and nonintegral/out-of-range counters, without leaking input values in errors.
Adapters must reject duplicate keys before map creation and enforce strict syntax,
UTF-8, Unicode, document bytes/depth/counts. Java allocation limits cannot protect
a decoder that already materialized an oversized document. Encode
minor-unit `long` values and timestamps as canonical decimal/ISO strings in
interchange fixtures rather than lossy JSON floating-point numbers. A broader
draft pack schema is not a promise that this prototype implements every feature.

The instrumentation-only Android adapter uses platform `JsonReader` plus the
shared `JsonLexicalGuard`: Android's non-lenient mode still accepts several invalid
lexemes. The guard is a linear lexical preflight, not a replacement structural
parser. Limits: 256 KiB document, 65,536 tokens including punctuation, 16 nested
containers, 256 characters per number. Duplicate decoded names are rejected even
when the first value is null. Exact BigDecimal numbers avoid double conversion.
Generic errors do not include raw document text or platform exception causes.

Repository tooling uses Python standard-library JSON decoding with duplicate-key
and exact supported-counter checks, then Gradle's bundled codec on canonical
output and the shared Java mapper. Python depth/token checks occur after decoding
a byte-bounded document: this is trusted build tooling, not an on-device importer.
The raw acceptance corpus is shared with Android tests; compiling Android tests
alone does not establish platform conformance. Generic fractional values outside
the pack's integer-counter schema are not a promise of cross-codec support.

## Literal grammar and account semantics

`Guard(line, literal, excluded)` checks literal containment (or absence) in the
whole message (`line = -1`) or one zero-based line. Guards are ANDed, exact and
case-sensitive. Sender matching does no prefix, suffix, telephone normalization,
trimming, case folding, or authentication.

`Field(line, after, before, maxLength)` operates inside its supplied scope:

1. Select the whole scope or one zero-based line within it.
2. A nonempty opening `after` anchor must occur exactly once in that selected
   scope, including overlapping occurrences. Missing is absent; repeated is
   ambiguous. An empty opening anchor means the scope's beginning.
3. A nonempty closing `before` anchor selects its **first** occurrence following
   the opening anchor. Empty means scope end. A missing closing anchor is absent.
4. Capture exactly the enclosed text, with no trimming. Empty means absent;
   exceeding `maxLength` is a limit failure. No truncation or regex is used.

Output `region` fields are relative to the message. All other fields in that
output, including currency/direction/date/original-amount fields, are relative to
that region. A line number is therefore local to its enclosing region. Regions
may overlap to share account/date labels; movement amount spans may not overlap
within a candidate. Equal amounts at disjoint spans remain separate events.
Closing delimiters such as `;` may repeat: only opening anchors require uniqueness.

LF and CRLF are supported. CRLF is treated as a line ending without rewriting
the body; provenance offsets are original UTF-16 offsets, end exclusive. Bare CR,
NUL, and malformed surrogate pairs are rejected. There is no Unicode folding or
global whitespace/bidi removal. Field-local transformations must be declared.

Fields optionally declare `normalization`, `numeric`, and `terminated`:

- `NONE` preserves raw capture text. `DIGITS` folds Persian/Arabic-Indic digits.
- `ACCOUNT` additionally trims edge spaces/tabs and bidi marks; it does not remove
  internal characters. `TEXT` removes bidi/joiner marks, collapses ASCII whitespace
  and folds digits. `CHANNEL` additionally folds Arabic yeh/kaf to Persian forms.
- `NumericShape(segments, mode)` validates one to four dot-separated bounded digit
  segments. `EXACT` consumes the complete capture; `PREFIX` starts at its beginning;
  `UNIQUE` searches the bounded capture and rejects multiple valid references.
  Adjoining Unicode decimal digits, dots and commas belong to the same token and
  cannot be silently truncated. Leading zeros remain strings.
- `terminated=true` requires the selected numbered line to end in LF/CRLF inside
  its region. Anchors always operate on raw text, and length caps apply before
  transformation. Reported spans enclose the original raw capture.

Role constraints prevent transformations from changing financial meaning:
money and date selectors must use `NONE` and no numeric selection. Their declared
digit policies alone govern numeral conversion. Currency/direction token selectors
allow `NONE` or explicit `TEXT`, with no numeric selection. Regions stay raw.
Numeric shapes and other normalization policies serve accounts/optional metadata.

Accounts are opaque case-sensitive references with ASCII letters, digits, dot and
hyphen; leading zeros are preserved. They are **not proof of a resolved identity**.
`accountOptional=true` permits an absent selector or invalid/missing reference:
the result is null plus `ACCOUNT_UNRESOLVED`, never a guessed identifier. Conflicting
account captures and resource failures still reject. `Fact.accountState()` reports
`REFERENCED` or `UNRESOLVED`. Mask linking, IBAN validation and ledger-resolution
eligibility are not implemented.
Facts do not authorize adding balances together or treating available/booked
balances as separate assets. Downstream ledger identity must include currency.

`TextRule(field, mapping, minLength, maxLength, digitFree)` emits optional
`SemanticText(id, text)` for reason/channel. Maps contain at most 16 known semantic
IDs. Length is checked on raw captured text; unknown/absent/invalid/ambiguous
optional text is omitted with diagnostics. Resource exhaustion remains fatal.

## Exact money

Registry `prototype-currencies-1`: IRR/JPY scale 0, USD/EUR/GBP scale 2, KWD/JOD
scale 3. `Money(currency, minorUnits, scale)` rejects a noncanonical scale. No FX
conversion or totals are performed. Negative balances and zero balances are valid.

`CurrencyRule(fixed, token, mapping)` supports fixed currency, finite extracted
token mapping, or both (mapped token must agree with fixed currency). Tokens match
exactly; `$` has no implicit meaning. A token/mapping must be declared together.
Unknown tokens and conflicting fixed/mapped currency are distinct diagnostic codes.
Uncaptured text is not currency evidence: declare token validation wherever needed.

`MoneyRule(amount, currency, decimal, group, grouping, digits, unitMultiplier,
sign, leadingPoint)` allows an optional single `+`/`-` in the declared position
(`sign`, LEADING by default, TRAILING after the digits) and, by default, at least
one integer digit before an optional decimal separator followed by digits.
`leadingPoint` (false by default) is the only widening: true reads an empty
integer part as zero, so a bank that prints amounts below one unit as `.11` is
readable. It is opt-in because a missing integer part otherwise signals a
misaligned capture, which must keep failing rather than become a fraction. The
allowed decimal separators are `.`, `,`, U+066B. Groups can be disabled or use
`.`, `,`, space, U+066C. Decimal and group characters differ. Western grouping is
1–3 digits then groups of 3; Indian grouping is 1–2 digits, middle groups of 2,
final group of 3. Ungrouped integers are accepted with either grouping policy.

Currency precision is a hard limit, including excess trailing zero digits.
No exponent, parentheses, trailing signs, implicit markers, rounding, or arbitrary
punctuation removal. Unit multiplier is 1, or explicitly 10 for IRR tomans.
Toman source fractions may have one digit, converted exactly to integer rials.
Conversion overflow rejects the entire candidate. Negative `Long.MIN_VALUE` is
valid; positive/unsigned magnitudes greater than `Long.MAX_VALUE` are rejected,
including before applying an unsigned debit direction.

`DirectionRule(fixed, token, mapping)` follows the same fixed/finite/agreement
contract as currency. Unsigned movement amounts adopt the declared direction;
an explicit sign must agree. `+` with debit or `-` with credit is invalid, and
zero movements are rejected rather than emitted. Original amounts must be
nonnegative and cannot contribute an extra ledger movement.

## Date prototype

The original `DateRule(field, orders, separator, withTime, digits, maxPast,
maxFuture)` explicitly declares Gregorian YMD, DMY, MDY or YDM. Separators are `/`, `-`, `.`;
year width is exactly four, month/day exactly two. Optional time is in the same
capture, exactly ` HH:mm`. Date-only interpretation uses local midnight and
retains DAY precision; timed fields retain MINUTE precision.

All declared orders are evaluated before plausibility filtering. Different valid
calendar interpretations yield `DATE_AMBIGUOUS` and arrival fallback even when
the arrival window could eliminate one. Multiple orders yielding the same date
are accepted. Invalid calendar dates, timezone gaps/overlaps, or out-of-window
dates also fall back with distinct diagnostics. Limits: past window at most 366
days, future window at most 2 days. No guessing century, trying another span,
or searching for an unrelated time. The supplied zone and arrival are preserved.
No date declaration also uses arrival with ARRIVAL precision and fallback=true.

The extended `DateOptions` explicitly sets calendar (`GREGORIAN`/`JALALI`), year
policy, year base, variable month/day widths, layout, clock separator, seconds and
zone. JSON declares `calendar` on the date object and the remaining seven fields
inside `options`. Without options, the original strict Gregorian behavior remains.

- `FULL`: four-digit year. `TWO_DIGIT`: two digits plus the declared base.
- `NEIGHBOR`: yearless `MD` or `DM`; independently validate arrival year −1/current/+1
  in the declared zone/calendar, including leap days, then filter by the window.
  Several viable years or different orders remain ambiguous.
- `COMPACT`: `MMDD<clock separator>HH:mm[:ss]`, with `MD`, `NEIGHBOR` and fixed widths.
  Separated month/day widths may be one/two digits when explicitly enabled.
- Clock separators are space, `-`, or `T`; seconds retain `SECOND` precision.
- Jalali years 1–3177 use the existing app's breakpoint arithmetic and truncating
  integer division. The standalone parity suite checks every month boundary in
  that range plus independent modern anchors; this is arithmetic parity, not a
  claim of identical legacy date-selection/zone behavior.

AM/PM, source-stated offsets, timezone-rule-version persistence, one-digit hours,
mixed separators and explicit DST-overlap choices remain deferred.
Equivalent IANA timezone data is needed for matching conversions across runtimes.

## Atomic acceptance, conflicts, and provenance

Candidates and outputs are evaluated in stable ID order, independent of input
list/set/map ordering. Every required output must validate before any of its facts
can survive. An invalid output yields no partial statement. Identical semantic
candidate multisets coalesce and keep all matching output provenance; the first
stable template key supplies the representative facts/IDs. Therefore adding a
coalescing rule can change representative provenance: persistence must not use
that representative as finalized occurrence identity.

Different bank/account/kind/currency/amount/original amount/event time/precision
interpretations produce AMBIGUOUS with no facts. A valid candidate competing with
a guard-matched invalid candidate is also conservatively AMBIGUOUS. Output IDs,
rule revisions and source spans are provenance, not semantic equality keys.
Duplicate semantic outputs inside one candidate retain multiplicity. This engine
does not deduplicate SMS occurrences or infer chronological ordering from dates.

Reason/channel are excluded from financial equivalence. After financial multiset
coalescing, each metadata field must agree, including presence and normalized text;
otherwise it is omitted with `OPTIONAL_CONFLICT`. For repeated identical financial
outputs across candidates, only group-wide unanimity is safe: differing metadata
is omitted across that duplicate group with `OPTIONAL_ASSOCIATION_AMBIGUOUS`.
Output IDs/spans do not prove cross-template correspondence. One template retains
its explicit per-output association. Multiplicity and all provenance survive.

Statuses distinguish UNKNOWN_SENDER, NO_MATCH, ABSENT, INVALID, OVERFLOW,
AMBIGUOUS, LIMIT_EXCEEDED and PARSED. With no valid candidate, failure precedence
is limit > ambiguity > overflow > invalid > absent. Date diagnostics do not turn
valid financial facts into failures; date field resource limits still reject.
Input/candidate/work limits abort globally. Results never publish facts unless
PARSED. Structured diagnostics explain specific causes, including conflicting
currency/direction, missing required fields and overlapping movement captures.

Provenance retains source ID, pack/revision/template/output IDs, engine version
`prototype-1`, and ledger amount source span. Event time separately retains zone,
precision/fallback, and original arrival. Full field-span provenance, pack digests,
source trust, finalized occurrence identity and persistent interpretation history
remain future contracts.

## Explicit bounds

| Dimension | Maximum |
| --- | ---: |
| Message body (UTF-16 code units) | 16,384 |
| Sender / source ID (UTF-16 code units) | 128 / 128 |
| Message lines | 256 |
| Templates per snapshot | 256 |
| Sender-indexed candidates per message, before guards | 32 |
| Senders / guards per template | 16 / 16 |
| Explicit required outputs per template | 8 |
| Literal / finite-map token length | 128 |
| Entries per finite mapping | 16 |
| Typed capture length | 256 |
| Output region length | 16,384 |
| Charged matcher work per message | 1,000,000 |

Literal search charges the literal length per tested position (an upper bound on
character comparisons); line selection charges scanned code units plus delimiter.
The budget is shared across candidates, outputs and optional dates. Exhaustion
returns WORK_LIMIT without partial results. Input validation and typed parsing
are separately bounded by input/capture/count caps. These are prototype safety
caps, not measured production performance targets or a claimed memory budget.

Representative Iranian fields/dates now have executable JSON and Android coverage.
Remaining integration gates include full legacy account/normalization parity,
ordered sender aliases and movement fallbacks, occurrence/output identity and
production resource budgets. Scan/storage experiments are evidence, not a completed
durable repository. No app migration or activation is implied by passing tests.
