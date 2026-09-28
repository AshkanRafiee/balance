# Portable parser prototype

A production-dependency-free Java 17 prototype for explicit, bounded bank-message rules.
It demonstrates exact currency amounts, coherent multiple outputs, deterministic
conflict handling, and explicitly ordered Gregorian/Jalali dates. It has no Android or
storage dependencies and does not change the application's current parser.

Production runtime APIs target Android 26 without core-library desugaring or added
libraries. Collection snapshots use null-rejecting defensive copies and immutable
wrappers. Java 17 records rely on the application's existing D8 record desugaring.

The supported API and grammar are specified in [CONTRACT.md](CONTRACT.md).
The synthetic fixtures include one account with USD/EUR balances, a foreign
purchase with a distinct ledger debit, and Iranian-style account, date, reason
and channel layouts. `JalaliParityTest` checks arithmetic against the existing app.

Run from the repository root:

```sh
./gradlew :parser-core:check
```

The module also has standalone settings. From `parser-core`, the repository's
existing wrapper can run its checks without configuring Android:

```sh
../gradlew --offline -p . check
```

The complete check task requires Python 3 for the repository-fixture frontend;
it uses only Python's standard library. Gradle's bundled Groovy codec consumes
its canonical output, then the same Java `PackDocument` mapper used by the Android
prototype validates the rule semantics. There are no new third-party production
or test libraries. The Java test tasks
also run independently of Python.

Independent of Gradle or the Android SDK, run from `parser-core` (verify the
temporary parent directory exists first):

```sh
ls /tmp/opencode
javac --release 17 -Xlint:all -Werror -d /tmp/opencode/parser-core-classes \
  src/main/java/com/ashkanrafiee/balance/parser/*.java \
  src/test/java/com/ashkanrafiee/balance/parser/PrototypeTest.java
java -cp /tmp/opencode/parser-core-classes com.ashkanrafiee.balance.parser.PrototypeTest
```

This is a Phase 1 feasibility surface, not a complete rules engine or a claim of
Iranian parsing parity. A draft JSON schema and executable synthetic examples live
under `rules/`. The Android JSON adapter is instrumentation-only while its strict
acceptance behavior is evaluated. Reviewed bank packs, full legacy behavior
compatibility and financial/application integration remain later work.

## Synthetic benchmark

From `parser-core`, run `../gradlew --offline -p . prototypeBenchmark` (or
`:parser-core:prototypeBenchmark` from the integrated parent build). The standalone
Java command also works after compiling `PrototypeBenchmark.java` alongside the
other sources above; its main class is
`com.ashkanrafiee.balance.parser.PrototypeBenchmark`.

The benchmark prints five samples each of fresh 32-template snapshot compilation,
warm parsing of short two-output inputs, and a maximum-length, near-matching
literal input that exhausts the work budget. Compilation excludes typed-rule
construction; "cold" means a new snapshot, not a fresh JVM. Parse scenarios have
explicit warm-up. Output contains durations, throughput, input lengths and numeric
checksums only. Timings include result/checksum consumption and are observational:
there are no performance pass/fail thresholds. These JVM measurements do not
establish Android scan, storage, allocation, or production performance budgets.
