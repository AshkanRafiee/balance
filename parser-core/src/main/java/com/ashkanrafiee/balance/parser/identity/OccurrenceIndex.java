package com.ashkanrafiee.balance.parser.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.crypto.SecretKey;

/**
 * Immutable, bounded index for one provider namespace. Persist the returned snapshot atomically
 * with consumer state. It contains sensitive evidence and belongs in encrypted storage.
 * No legacy-row, event-deduplication, note, retraction or automatic reassociation semantics.
 * A caller owns concurrency and the stable evidence key. Silent identical row reuse within
 * established continuity is indistinguishable from a refresh.
 */
public final class OccurrenceIndex {
    public static final int SNAPSHOT_VERSION = 1;
    public static final int MAX_OCCURRENCES = 100_000;
    public static final int MAX_EPOCHS = 1024;

    public enum Status { OBSERVED, UNRESOLVED }

    public static final class Binding {
        private final String namespace;
        private final String epoch;
        private final long rowId;

        public Binding(String namespace, String epoch, long rowId) {
            this.namespace = Names.require(namespace);
            this.epoch = Names.require(epoch);
            if (rowId < 0) throw new IllegalArgumentException("Negative provider row ID");
            this.rowId = rowId;
        }
        public String namespace() { return namespace; }
        public String epoch() { return epoch; }
        public long rowId() { return rowId; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Binding)) return false;
            Binding that = (Binding) other;
            return rowId == that.rowId && namespace.equals(that.namespace) && epoch.equals(that.epoch);
        }
        @Override public int hashCode() { return Objects.hash(namespace, epoch, rowId); }
        @Override public String toString() { return "Binding[redacted]"; }
    }

    /** An explicit caller boundary. Uncertain epochs keep even unmatched rows unresolved. */
    public static final class Epoch {
        private final String id;
        private final boolean uncertain;
        public Epoch(String id, boolean uncertain) {
            this.id = Names.require(id);
            this.uncertain = uncertain;
        }
        public String id() { return id; }
        public boolean uncertain() { return uncertain; }
        @Override public String toString() { return "Epoch[redacted]"; }
    }

    public static final class Occurrence {
        private final String id;
        private final Binding binding;
        private final EnvelopeEvidence evidence;
        private final Status status;

        public Occurrence(String id, Binding binding, EnvelopeEvidence evidence, Status status) {
            this.id = Names.require(id);
            this.binding = Objects.requireNonNull(binding, "binding");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
            this.status = Objects.requireNonNull(status, "status");
        }
        public String id() { return id; }
        public Binding binding() { return binding; }
        public EnvelopeEvidence evidence() { return evidence; }
        public Status status() { return status; }
        public IdentityKeys.Output output(String slot) { return new IdentityKeys.Output(id, slot); }
        @Override public String toString() { return "Occurrence[redacted]"; }
    }

    /** Structured transport model. Ordering is chronological insertion order and is significant. */
    public static final class Snapshot {
        private final int version;
        private final String namespace;
        private final int limit;
        private final List<Epoch> epochs;
        private final List<Occurrence> occurrences;

        public Snapshot(int version, String namespace, int limit, List<Epoch> epochs,
                List<Occurrence> occurrences) {
            if (version != SNAPSHOT_VERSION) throw new IllegalArgumentException("Unsupported snapshot version");
            checkLimit(limit);
            this.version = version;
            this.namespace = Names.require(namespace);
            this.limit = limit;
            this.epochs = copy(epochs, MAX_EPOCHS);
            this.occurrences = copy(occurrences, limit);
        }
        public int version() { return version; }
        public String namespace() { return namespace; }
        public int limit() { return limit; }
        public List<Epoch> epochs() { return epochs; }
        public List<Occurrence> occurrences() { return occurrences; }
        @Override public String toString() { return "Snapshot[redacted]"; }
    }

    public static final class Ingest {
        private final OccurrenceIndex index;
        private final Occurrence occurrence;
        private final boolean created;
        private final List<String> candidates;

        private Ingest(OccurrenceIndex index, Occurrence occurrence, boolean created) {
            this.index = index;
            this.occurrence = occurrence;
            this.created = created;
            this.candidates = index.candidates(occurrence.id());
        }
        public OccurrenceIndex index() { return index; }
        public Occurrence occurrence() { return occurrence; }
        public boolean created() { return created; }
        /** Equal evidence in prior epochs only; even a single candidate is not proof. */
        public List<String> candidates() { return candidates; }
        @Override public String toString() { return "Ingest[redacted]"; }
    }

    private final Snapshot snapshot;
    private final Map<Binding, Occurrence> latest;
    private final Map<String, Occurrence> byId;
    private final Map<String, Integer> epochPositions;

    private OccurrenceIndex(Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.epochs.isEmpty()) throw new IllegalArgumentException("Missing epoch");
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < snapshot.epochs.size(); i++) {
            if (positions.put(snapshot.epochs.get(i).id, i) != null) {
                throw new IllegalArgumentException("Duplicate epoch");
            }
        }
        Map<Binding, Occurrence> bindings = new HashMap<>();
        Map<String, Occurrence> ids = new HashMap<>();
        int previousPosition = -1;
        for (Occurrence occurrence : snapshot.occurrences) {
            Binding binding = occurrence.binding;
            Integer position = positions.get(binding.epoch);
            if (!snapshot.namespace.equals(binding.namespace) || position == null || position < previousPosition) {
                throw new IllegalArgumentException("Invalid snapshot binding order or scope");
            }
            Status expected = snapshot.epochs.get(position).uncertain ? Status.UNRESOLVED : Status.OBSERVED;
            if (occurrence.status != expected || ids.put(occurrence.id, occurrence) != null) {
                throw new IllegalArgumentException("Invalid snapshot occurrence");
            }
            Occurrence previous = bindings.put(binding, occurrence);
            if (previous != null && previous.evidence.equals(occurrence.evidence)) {
                throw new IllegalArgumentException("Duplicate snapshot observation");
            }
            previousPosition = position;
        }
        this.latest = Collections.unmodifiableMap(bindings);
        this.byId = Collections.unmodifiableMap(ids);
        this.epochPositions = Collections.unmodifiableMap(positions);
    }

    public static OccurrenceIndex empty(String namespace, String epoch, int limit) {
        return new OccurrenceIndex(new Snapshot(SNAPSHOT_VERSION, namespace, limit,
                Collections.singletonList(new Epoch(epoch, false)), Collections.emptyList()));
    }

    public static OccurrenceIndex restore(Snapshot snapshot) { return new OccurrenceIndex(snapshot); }
    public Snapshot snapshot() { return snapshot; }
    public Epoch activeEpoch() { return snapshot.epochs.get(snapshot.epochs.size() - 1); }

    /** Starts an uncertain restore/reset interval. Old bindings remain available as history. */
    public OccurrenceIndex switchEpoch(String epoch) { return appendEpoch(epoch, true); }

    /**
     * Explicit caller assertion that subsequent arrivals are outside the uncertain interval.
     * Opens a fresh epoch; never promotes/rebinds existing unresolved observations.
     */
    public OccurrenceIndex startEstablishedEpoch(String epoch) { return appendEpoch(epoch, false); }

    private OccurrenceIndex appendEpoch(String epoch, boolean uncertain) {
        Names.require(epoch);
        if (epochPositions.containsKey(epoch)) throw new IllegalArgumentException("Epoch already used");
        if (snapshot.epochs.size() >= MAX_EPOCHS) throw new IllegalStateException("Epoch limit exceeded");
        List<Epoch> epochs = new ArrayList<>(snapshot.epochs);
        epochs.add(new Epoch(epoch, uncertain));
        return restore(new Snapshot(SNAPSHOT_VERSION, snapshot.namespace, snapshot.limit, epochs, snapshot.occurrences));
    }

    public Ingest ingest(Binding binding, EnvelopeEvidence evidence) {
        return ingest(binding, evidence, () -> UUID.randomUUID().toString());
    }

    public Ingest ingest(Binding binding, String keyId, SecretKey key, String sender,
            String body, long arrivalMillis, Supplier<String> ids) {
        return ingest(binding, EnvelopeEvidence.compute(keyId, key, sender, body, arrivalMillis), ids);
    }

    /** IDs must be fresh opaque random names. A collision fails atomically without retry loops. */
    public Ingest ingest(Binding binding, EnvelopeEvidence evidence, Supplier<String> ids) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(ids, "ids");
        if (!snapshot.namespace.equals(binding.namespace) || !activeEpoch().id.equals(binding.epoch)) {
            throw new IllegalArgumentException("Binding is outside active provider epoch");
        }
        Occurrence previous = latest.get(binding);
        if (previous != null && previous.evidence.equals(evidence)) return new Ingest(this, previous, false);
        if (snapshot.occurrences.size() >= snapshot.limit) throw new IllegalStateException("Occurrence limit exceeded");
        String id = Names.require(ids.get());
        if (byId.containsKey(id)) throw new IllegalArgumentException("Occurrence ID collision");
        Occurrence occurrence = new Occurrence(id, binding, evidence,
                activeEpoch().uncertain ? Status.UNRESOLVED : Status.OBSERVED);
        List<Occurrence> occurrences = new ArrayList<>(snapshot.occurrences);
        occurrences.add(occurrence);
        OccurrenceIndex next = restore(new Snapshot(SNAPSHOT_VERSION, snapshot.namespace,
                snapshot.limit, snapshot.epochs, occurrences));
        return new Ingest(next, occurrence, true);
    }

    /** Full multiplicity, without greedy matching or candidate consumption. */
    public List<String> candidates(String occurrenceId) {
        Occurrence target = byId.get(Names.require(occurrenceId));
        if (target == null) throw new IllegalArgumentException("Unknown occurrence ID");
        if (target.status != Status.UNRESOLVED) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        int targetEpoch = epochPositions.get(target.binding.epoch);
        for (Occurrence occurrence : snapshot.occurrences) {
            if (epochPositions.get(occurrence.binding.epoch) < targetEpoch
                    && occurrence.evidence.equals(target.evidence)) result.add(occurrence.id);
        }
        return Collections.unmodifiableList(result);
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_OCCURRENCES) throw new IllegalArgumentException("Invalid occurrence limit");
    }

    private static <T> List<T> copy(List<T> source, int limit) {
        Objects.requireNonNull(source, "list");
        if (source.size() > limit) throw new IllegalArgumentException("Snapshot limit exceeded");
        List<T> result = new ArrayList<>(source.size());
        for (T item : source) result.add(Objects.requireNonNull(item, "list item"));
        return Collections.unmodifiableList(result);
    }

    @Override public String toString() { return "OccurrenceIndex[redacted]"; }
}
