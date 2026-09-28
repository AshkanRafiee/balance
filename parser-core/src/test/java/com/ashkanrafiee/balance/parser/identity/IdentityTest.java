package com.ashkanrafiee.balance.parser.identity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Dependency-free synthetic contract suite. Run its main with assertions enabled or disabled. */
public final class IdentityTest {
    private static int checks;
    private static final SecretKey KEY = key();

    public static void main(String[] args) {
        exactEvidence();
        multiplicityAndReuse();
        restoreCandidates();
        snapshotsAndLimits();
        outputAndLedgerKeys();
        diagnostics();
        System.out.println("IdentityTest: " + checks + " checks passed");
    }

    private static SecretKey key() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static EnvelopeEvidence evidence(String sender, String body, long arrival) {
        return EnvelopeEvidence.compute("key-1", KEY, sender, body, arrival);
    }

    private static void exactEvidence() {
        EnvelopeEvidence first = evidence("ab", "c", 123);
        // Independent Python stdlib hmac/struct vector, not an encoding implementation in this test.
        equal("22cb73c095b9cfa319b137b41dfefd6d1d7e291468fde0e6d70e5cac7ef294b8", hex(first.digest()));
        equal(first, evidence("ab", "c", 123));
        different(first, evidence("a", "bc", 123));
        different(first, evidence("ab", "c", 124));
        different(first, evidence("AB", "c", 123));
        different(first, evidence("ab", "c ", 123));
        different(evidence("", "a\nb", 0), evidence("", "a\r\nb", 0));
        different(evidence("", "\u00e9", 0), evidence("", "e\u0301", 0));
        different(evidence("", "\ud800", 0), evidence("", "\ud801", 0));
        different(evidence("a\u0000", "b", 0), evidence("a", "\u0000b", 0));
        different(first, EnvelopeEvidence.compute("key-2", KEY, "ab", "c", 123));
        different(first, EnvelopeEvidence.compute("key-1", new SecretKeySpec(new byte[32], "HmacSHA256"),
                "ab", "c", 123));
        equal(Long.MAX_VALUE, evidence("", "", Long.MAX_VALUE).arrivalMillis());
        byte[] exported = first.digest();
        EnvelopeEvidence imported = new EnvelopeEvidence(1, "key-1", 123, exported);
        exported[0] ^= 1;
        equal(first, imported);
        byte[] extracted = imported.digest();
        extracted[1] ^= 1;
        equal(first, imported);
        equal(first.hashCode(), imported.hashCode());
        rejects(IllegalArgumentException.class, () -> new EnvelopeEvidence(2, "key-1", 0, new byte[32]));
        rejects(IllegalArgumentException.class, () -> new EnvelopeEvidence(1, "key-1", -1, new byte[32]));
        rejects(IllegalArgumentException.class, () -> new EnvelopeEvidence(1, "key-1", 0, new byte[31]));
        rejects(NullPointerException.class, () -> new EnvelopeEvidence(1, "key-1", 0, null));
        rejects(NullPointerException.class, () -> evidence(null, "b", 0));
        rejects(NullPointerException.class, () -> evidence("s", null, 0));
        rejects(NullPointerException.class, () -> EnvelopeEvidence.compute("key-1", null, "s", "b", 0));
        rejects(IllegalArgumentException.class, () -> evidence("s", "b", -1));
        evidence(repeat('s', EnvelopeEvidence.MAX_SENDER_CHARS), "", 0);
        rejects(IllegalArgumentException.class, () -> evidence(repeat('s', EnvelopeEvidence.MAX_SENDER_CHARS + 1), "", 0));
        evidence("", repeat('b', EnvelopeEvidence.MAX_BODY_CHARS), 0);
        rejects(IllegalArgumentException.class, () -> evidence("", repeat('b', EnvelopeEvidence.MAX_BODY_CHARS + 1), 0));
    }

    private static void multiplicityAndReuse() {
        OccurrenceIndex empty = OccurrenceIndex.empty("sms", "e1", 10);
        AtomicInteger counter = new AtomicInteger();
        Supplier<String> ids = () -> "occ-" + counter.incrementAndGet();
        EnvelopeEvidence debit = evidence("bank", "Debit USD 5; balance 95", 100);
        OccurrenceIndex.Ingest a = empty.ingest(binding("e1", 41), debit, ids);
        OccurrenceIndex.Ingest b = a.index().ingest(binding("e1", 42), debit, ids);
        equal(0, empty.snapshot().occurrences().size());
        equal(1, a.index().snapshot().occurrences().size());
        equal(2, b.index().snapshot().occurrences().size());
        different(a.occurrence().id(), b.occurrence().id());
        equal(OccurrenceIndex.Status.OBSERVED, a.occurrence().status());
        check(a.created() && b.created(), "Separate rows must create separate occurrences");
        OccurrenceIndex.Ingest refresh = b.index().ingest(binding("e1", 41), debit, ids);
        check(!refresh.created() && refresh.index() == b.index(), "Refresh must be a no-op");
        equal(a.occurrence().id(), refresh.occurrence().id());
        equal(2, counter.get());
        EnvelopeEvidence changed = evidence("bank", "Debit USD 7; balance 93", 100);
        OccurrenceIndex.Ingest replacement = refresh.index().ingest(binding("e1", 41), changed, ids);
        different(a.occurrence().id(), replacement.occurrence().id());
        equal(debit, replacement.index().snapshot().occurrences().get(0).evidence());
        equal(3, replacement.index().snapshot().occurrences().size());
        OccurrenceIndex restored = OccurrenceIndex.restore(rebuild(replacement.index().snapshot()));
        equal(replacement.occurrence().id(), restored.ingest(binding("e1", 41), changed, ids).occurrence().id());
        // Returning to an old envelope after observed reuse is not a refresh of the old occurrence.
        OccurrenceIndex.Ingest again = restored.ingest(binding("e1", 41), debit, ids);
        equal("occ-4", again.occurrence().id());
        equal(4, again.index().snapshot().occurrences().size());
        OccurrenceIndex reloaded = OccurrenceIndex.restore(rebuild(again.index().snapshot()));
        check(!reloaded.ingest(binding("e1", 41), debit, ids).created(), "Latest binding must survive restore");
        rejects(IllegalArgumentException.class, () -> reloaded.ingest(binding("e1", 43), debit, () -> "occ-1"));
        equal(4, reloaded.snapshot().occurrences().size());
        rejects(IllegalArgumentException.class, () -> reloaded.ingest(new OccurrenceIndex.Binding("other", "e1", 41), debit, ids));
        OccurrenceIndex.Ingest explicit = empty.ingest(binding("e1", 0), "key-1", KEY, "bank", "body", 0, () -> "explicit");
        equal(evidence("bank", "body", 0), explicit.occurrence().evidence());
        OccurrenceIndex.Ingest random = empty.ingest(binding("e1", Long.MAX_VALUE), debit);
        equal(36, random.occurrence().id().length());
        equal(Long.MAX_VALUE, random.occurrence().binding().rowId());
    }

    private static void restoreCandidates() {
        EnvelopeEvidence same = evidence("bank", "same", 100);
        OccurrenceIndex old = OccurrenceIndex.empty("sms", "e1", 20)
                .ingest(binding("e1", 41), same, () -> "a").index()
                .ingest(binding("e1", 42), same, () -> "b").index();
        OccurrenceIndex uncertain = old.switchEpoch("restore");
        OccurrenceIndex.Ingest x = uncertain.ingest(binding("restore", 901), same, () -> "x");
        OccurrenceIndex.Ingest y = x.index().ingest(binding("restore", 902), same, () -> "y");
        equal(Arrays.asList("a", "b"), x.candidates());
        equal(Arrays.asList("a", "b"), y.candidates());
        equal(Arrays.asList("a", "b"), y.index().candidates("x"));
        equal(OccurrenceIndex.Status.UNRESOLVED, x.occurrence().status());
        equal(OccurrenceIndex.Status.UNRESOLVED, y.occurrence().status());
        different(x.occurrence().id(), y.occurrence().id());
        equal(4, y.index().snapshot().occurrences().size());
        equal(2, old.snapshot().occurrences().size());
        OccurrenceIndex roundTrip = OccurrenceIndex.restore(rebuild(y.index().snapshot()));
        OccurrenceIndex.Ingest refresh = roundTrip.ingest(binding("restore", 901), same, () -> { throw new AssertionError("ID allocated on refresh"); });
        equal("x", refresh.occurrence().id());
        equal(Arrays.asList("a", "b"), refresh.candidates());
        OccurrenceIndex.Ingest unmatched = roundTrip.ingest(binding("restore", 903), evidence("bank", "new", 100), () -> "z");
        equal(OccurrenceIndex.Status.UNRESOLVED, unmatched.occurrence().status());
        check(unmatched.candidates().isEmpty(), "Unmatched restore row is still unresolved");
        rejects(UnsupportedOperationException.class, () -> x.candidates().add("bad"));
        rejects(IllegalArgumentException.class, () -> roundTrip.ingest(binding("e1", 41), same, () -> "bad"));
        rejects(IllegalArgumentException.class, () -> roundTrip.switchEpoch("e1"));
        rejects(IllegalArgumentException.class, () -> roundTrip.candidates("missing"));
        OccurrenceIndex outside = unmatched.index().startEstablishedEpoch("live");
        OccurrenceIndex.Ingest live = outside.ingest(binding("live", 904), same, () -> "live-row");
        equal(OccurrenceIndex.Status.OBSERVED, live.occurrence().status());
        check(live.candidates().isEmpty(), "Outside explicit interval there is no text dedup");
        equal(Arrays.asList("a", "b"), live.index().candidates("x"));
        equal(OccurrenceIndex.Status.UNRESOLVED, live.index().snapshot().occurrences().get(2).status());
        OccurrenceIndex singleton = OccurrenceIndex.empty("sms", "initial", 4)
                .ingest(binding("initial", 1), same, () -> "only-old").index().switchEpoch("new");
        OccurrenceIndex.Ingest one = singleton.ingest(binding("new", 1), same, () -> "only-new");
        equal(Collections.singletonList("only-old"), one.candidates());
        equal(OccurrenceIndex.Status.UNRESOLVED, one.occurrence().status());
        different("only-old", one.occurrence().id());
    }

    private static void snapshotsAndLimits() {
        EnvelopeEvidence e = evidence("s", "b", 0);
        OccurrenceIndex full = OccurrenceIndex.empty("sms", "e1", 1).ingest(binding("e1", 1), e, () -> "a").index();
        check(!full.ingest(binding("e1", 1), e, () -> "b").created(), "Refresh works at capacity");
        rejects(IllegalStateException.class, () -> full.ingest(binding("e1", 2), e, () -> "b"));
        rejects(IllegalStateException.class, () -> full.ingest(binding("e1", 1), evidence("s", "c", 0), () -> "b"));
        rejects(IllegalArgumentException.class, () -> OccurrenceIndex.empty("sms", "e1", 0));
        rejects(IllegalArgumentException.class, () -> OccurrenceIndex.empty("sms", "e1", Integer.MAX_VALUE));
        rejects(IllegalArgumentException.class, () -> new OccurrenceIndex.Binding("sms", "e1", -1));
        List<OccurrenceIndex.Epoch> epochs = new ArrayList<>(full.snapshot().epochs());
        List<OccurrenceIndex.Occurrence> rows = new ArrayList<>(full.snapshot().occurrences());
        OccurrenceIndex.Snapshot snapshot = new OccurrenceIndex.Snapshot(1, "sms", 1, epochs, rows);
        epochs.clear();
        rows.clear();
        equal(1, snapshot.epochs().size());
        equal(1, snapshot.occurrences().size());
        rejects(UnsupportedOperationException.class, () -> snapshot.epochs().clear());
        rejects(UnsupportedOperationException.class, () -> snapshot.occurrences().clear());
        rejects(NullPointerException.class, () -> OccurrenceIndex.restore(null));
        rejects(NullPointerException.class, () -> new OccurrenceIndex.Snapshot(1, "sms", 1, null, rows));
        rejects(NullPointerException.class, () -> new OccurrenceIndex.Snapshot(1, "sms", 1, Arrays.asList((OccurrenceIndex.Epoch) null), rows));
        rejects(NullPointerException.class, () -> new OccurrenceIndex.Snapshot(1, "sms", 1, snapshot.epochs(), Arrays.asList((OccurrenceIndex.Occurrence) null)));
        rejects(IllegalArgumentException.class, () -> new OccurrenceIndex.Snapshot(2, "sms", 1, snapshot.epochs(), rows));
        rejects(IllegalArgumentException.class, () -> OccurrenceIndex.restore(new OccurrenceIndex.Snapshot(1, "sms", 1, epochs, rows)));
        rejects(IllegalArgumentException.class, () -> new OccurrenceIndex.Snapshot(1, "sms", 1, snapshot.epochs(), Arrays.asList(snapshot.occurrences().get(0), snapshot.occurrences().get(0))));
        List<OccurrenceIndex.Epoch> twoEpochs = Arrays.asList(new OccurrenceIndex.Epoch("e1", false), new OccurrenceIndex.Epoch("e2", true));
        OccurrenceIndex.Occurrence a = occurrence("a", "e1", 1, e, OccurrenceIndex.Status.OBSERVED);
        OccurrenceIndex.Occurrence duplicateId = occurrence("a", "e1", 2, e, OccurrenceIndex.Status.OBSERVED);
        OccurrenceIndex.Occurrence duplicateBinding = occurrence("b", "e1", 1, e, OccurrenceIndex.Status.OBSERVED);
        invalidSnapshot(twoEpochs, Arrays.asList(a, duplicateId));
        invalidSnapshot(twoEpochs, Arrays.asList(a, duplicateBinding));
        invalidSnapshot(twoEpochs, Collections.singletonList(occurrence("a", "unknown", 1, e, OccurrenceIndex.Status.OBSERVED)));
        invalidSnapshot(twoEpochs, Collections.singletonList(occurrence("a", "e2", 1, e, OccurrenceIndex.Status.OBSERVED)));
        invalidSnapshot(twoEpochs, Collections.singletonList(new OccurrenceIndex.Occurrence("a", new OccurrenceIndex.Binding("foreign", "e1", 1), e, OccurrenceIndex.Status.OBSERVED)));
        OccurrenceIndex.Occurrence later = occurrence("b", "e2", 1, e, OccurrenceIndex.Status.UNRESOLVED);
        invalidSnapshot(twoEpochs, Arrays.asList(later, a));
        invalidSnapshot(Arrays.asList(twoEpochs.get(0), twoEpochs.get(0)), Collections.emptyList());
        List<OccurrenceIndex.Epoch> maxEpochs = new ArrayList<>();
        for (int i = 0; i < OccurrenceIndex.MAX_EPOCHS; i++) maxEpochs.add(new OccurrenceIndex.Epoch("e" + i, true));
        OccurrenceIndex epochFull = OccurrenceIndex.restore(new OccurrenceIndex.Snapshot(1, "sms", 1, maxEpochs, Collections.emptyList()));
        rejects(IllegalStateException.class, () -> epochFull.switchEpoch("overflow"));
        maxEpochs.add(new OccurrenceIndex.Epoch("overflow", true));
        rejects(IllegalArgumentException.class, () -> new OccurrenceIndex.Snapshot(1, "sms", 1, maxEpochs, Collections.emptyList()));
        OccurrenceIndex empty = OccurrenceIndex.empty("sms", "e1", 1);
        rejects(NullPointerException.class, () -> empty.ingest(null, e));
        rejects(NullPointerException.class, () -> empty.ingest(binding("e1", 1), null));
        rejects(NullPointerException.class, () -> empty.ingest(binding("e1", 1), e, null));
        rejects(NullPointerException.class, () -> empty.ingest(binding("e1", 1), e, () -> null));
        rejects(IllegalArgumentException.class, () -> empty.ingest(binding("e1", 1), e, () -> "bad:id"));
        equal(0, empty.snapshot().occurrences().size());
    }

    private static void outputAndLedgerKeys() {
        OccurrenceIndex.Occurrence row = OccurrenceIndex.empty("sms", "e1", 2)
                .ingest(binding("e1", 1), evidence("s", "USD 10 EUR 20", 0), () -> "occ").occurrence();
        List<String> before = Arrays.asList(row.output("usd").id(), row.output("eur").id());
        // A revision reorders declarations; the slot name, not its ordinal or money, is the key.
        List<String> after = Arrays.asList(new IdentityKeys.Output("occ", "eur").id(), new IdentityKeys.Output("occ", "usd").id());
        equal(before.get(0), after.get(1));
        equal(before.get(1), after.get(0));
        equal("output-v1:occ:usd", before.get(0));
        equal(row.output("usd"), new IdentityKeys.Output("occ", "usd"));
        equal(row.output("usd").hashCode(), new IdentityKeys.Output("occ", "usd").hashCode());
        different(row.output("usd"), new IdentityKeys.Output("other", "usd"));
        different(row.output("usd"), row.output("fee"));
        for (String bad : Arrays.asList("", "a:b", " a", "a b", "a\n", "\u202e", repeat('x', 129))) {
            rejects(IllegalArgumentException.class, () -> row.output(bad));
            rejects(IllegalArgumentException.class, () -> new IdentityKeys.Output(bad, "usd"));
        }
        rejects(NullPointerException.class, () -> row.output(null));
        equal(128, row.output(repeat('x', 128)).slot().length());
        IdentityKeys.Account account = new IdentityKeys.Account("bank", "entity-1");
        IdentityKeys.Account sameAccount = new IdentityKeys.Account("bank", "entity-1");
        equal(account, sameAccount);
        equal(account.hashCode(), sameAccount.hashCode());
        different(account, new IdentityKeys.Account("other-bank", "entity-1"));
        different(account, new IdentityKeys.Account("bank", "entity-2"));
        IdentityKeys.Ledger usd = new IdentityKeys.Ledger(account, "USD", "deposit");
        equal(usd, new IdentityKeys.Ledger(sameAccount, "USD", "deposit"));
        equal(usd.hashCode(), new IdentityKeys.Ledger(sameAccount, "USD", "deposit").hashCode());
        different(usd, new IdentityKeys.Ledger(account, "EUR", "deposit"));
        different(usd, new IdentityKeys.Ledger(account, "USD", "debt"));
        different(usd, new IdentityKeys.Ledger(new IdentityKeys.Account("bank", "entity-2"), "USD", "deposit"));
        for (String invalid : Arrays.asList("usd", "Usd", " USD", "US", "US1", "USDD")) {
            rejects(IllegalArgumentException.class, () -> new IdentityKeys.Ledger(account, invalid, "deposit"));
        }
        rejects(NullPointerException.class, () -> new IdentityKeys.Account(null, "entity"));
        rejects(NullPointerException.class, () -> new IdentityKeys.Account("bank", null));
        rejects(NullPointerException.class, () -> new IdentityKeys.Ledger(null, "USD", "deposit"));
        rejects(NullPointerException.class, () -> new IdentityKeys.Ledger(account, null, "deposit"));
        rejects(NullPointerException.class, () -> new IdentityKeys.Ledger(account, "USD", null));
    }

    private static void diagnostics() {
        String sensitive = "synthetic-sensitive-account";
        IdentityKeys.Account account = new IdentityKeys.Account(sensitive, sensitive);
        EnvelopeEvidence e = EnvelopeEvidence.compute(sensitive, KEY, sensitive, sensitive, 123);
        OccurrenceIndex index = OccurrenceIndex.empty(sensitive, sensitive, 2);
        OccurrenceIndex.Binding binding = new OccurrenceIndex.Binding(sensitive, sensitive, 123);
        OccurrenceIndex.Ingest result = index.ingest(binding, e, () -> sensitive);
        for (Object value : Arrays.asList(account, new IdentityKeys.Ledger(account, "USD", sensitive),
                e, binding, result, result.occurrence(), result.occurrence().output(sensitive),
                result.index(), result.index().snapshot(), result.index().activeEpoch())) {
            check(!value.toString().contains(sensitive), "Diagnostics must redact identifiers");
            check(!value.toString().contains(hex(e.digest())), "Diagnostics must redact evidence");
        }
        try {
            new IdentityKeys.Account("bank", sensitive + ":bad");
            throw new AssertionError("Invalid name accepted");
        } catch (IllegalArgumentException expected) {
            check(!expected.toString().contains(sensitive), "Validation must not echo input");
        }
    }

    private static OccurrenceIndex.Binding binding(String epoch, long row) {
        return new OccurrenceIndex.Binding("sms", epoch, row);
    }

    private static OccurrenceIndex.Occurrence occurrence(String id, String epoch, long row,
            EnvelopeEvidence evidence, OccurrenceIndex.Status status) {
        return new OccurrenceIndex.Occurrence(id, binding(epoch, row), evidence, status);
    }

    private static void invalidSnapshot(List<OccurrenceIndex.Epoch> epochs, List<OccurrenceIndex.Occurrence> rows) {
        rejects(IllegalArgumentException.class, () -> OccurrenceIndex.restore(new OccurrenceIndex.Snapshot(1, "sms", 10, epochs, rows)));
    }

    /** Reconstruct every transport object to model decoding without introducing a codec. */
    private static OccurrenceIndex.Snapshot rebuild(OccurrenceIndex.Snapshot snapshot) {
        List<OccurrenceIndex.Epoch> epochs = new ArrayList<>();
        for (OccurrenceIndex.Epoch epoch : snapshot.epochs()) epochs.add(new OccurrenceIndex.Epoch(epoch.id(), epoch.uncertain()));
        List<OccurrenceIndex.Occurrence> rows = new ArrayList<>();
        for (OccurrenceIndex.Occurrence row : snapshot.occurrences()) {
            OccurrenceIndex.Binding b = row.binding();
            EnvelopeEvidence e = row.evidence();
            rows.add(new OccurrenceIndex.Occurrence(row.id(), new OccurrenceIndex.Binding(b.namespace(), b.epoch(), b.rowId()),
                    new EnvelopeEvidence(e.version(), e.keyId(), e.arrivalMillis(), e.digest()), row.status()));
        }
        return new OccurrenceIndex.Snapshot(snapshot.version(), snapshot.namespace(), snapshot.limit(), epochs, rows);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    private static void equal(Object expected, Object actual) {
        check(expected.equals(actual), "Values must be equal");
    }

    private static void different(Object first, Object second) {
        check(!first.equals(second), "Values must be distinct");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> expected, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("Wrong failure type", failure);
        }
        throw new AssertionError("Expected rejection");
    }
}
