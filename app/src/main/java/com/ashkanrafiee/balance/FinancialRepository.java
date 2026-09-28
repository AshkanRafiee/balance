package com.ashkanrafiee.balance;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** A small transactional facade over a versioned financial store. */
final class FinancialRepository {
    public interface Backend {
        Snapshot load() throws IOException;
        /** Called only for changed values; a no-op transaction performs no commit or CAS check. */
        void commit(Snapshot base, Map<String, byte[]> values) throws IOException;
    }

    public interface Work<T> {
        T run(Draft draft) throws Exception;
    }

    public static final class Snapshot {
        private final String revision;
        private final Map<String, byte[]> values;

        public Snapshot(String revision, Map<String, byte[]> values) {
            this(revision, values, false);
        }

        // Owned maps are private, and their byte arrays are never mutated internally.
        private Snapshot(String revision, Map<String, byte[]> values, boolean owned) {
            if (revision == null || values == null) throw new IllegalArgumentException("ARGUMENT");
            this.revision = revision;
            this.values = owned ? values : copy(values);
        }

        public String revision() { return revision; }

        public byte[] get(String name) {
            byte[] value = values.get(name);
            return value == null ? null : value.clone();
        }

        public Map<String, byte[]> components() { return copy(values); }

        private static Map<String, byte[]> copy(Map<String, byte[]> source) {
            Map<String, byte[]> result = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null)
                    throw new IllegalArgumentException("ARGUMENT");
                result.put(entry.getKey(), entry.getValue().clone());
            }
            return result;
        }
    }

    public static final class Draft {
        private final Map<String, byte[]> values;

        // Share owned bytes only: put replaces with a clone, and reads return copies.
        private Draft(Map<String, byte[]> values) { this.values = new LinkedHashMap<>(values); }

        public byte[] get(String name) {
            byte[] value = values.get(name);
            return value == null ? null : value.clone();
        }

        public void put(String name, byte[] value) {
            if (name == null || value == null) throw new IllegalArgumentException("ARGUMENT");
            values.put(name, value.clone());
        }

        public void remove(String name) { values.remove(name); }

        public Map<String, byte[]> componentsCopy() { return Snapshot.copy(values); }

        public Map<String, byte[]> components() { return componentsCopy(); }
    }

    private static final class State {
        final FinancialRepository owner;
        final Snapshot base;
        final Draft draft;
        boolean rollbackOnly;

        State(FinancialRepository owner, Snapshot base) {
            this.owner = owner;
            this.base = base;
            this.draft = new Draft(base.values);
        }
    }

    private static final ThreadLocal<ArrayDeque<State>> ACTIVE =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Boolean> IN_BACKEND =
            ThreadLocal.withInitial(() -> false);

    private final Backend backend;

    public FinancialRepository(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("ARGUMENT");
        this.backend = backend;
    }

    public static Backend generationBackend(EncryptedGenerationStore store) {
        if (store == null) throw new IllegalArgumentException("ARGUMENT");
        return new Backend() {
            @Override public Snapshot load() throws IOException {
                EncryptedGenerationStore.Snapshot saved = store.getSnapshot();
                return new Snapshot(saved.generation(), saved.components(), true);
            }

            @Override public void commit(Snapshot base, Map<String, byte[]> values) throws IOException {
                store.commit(base.revision(), values);
            }
        };
    }

    public Snapshot snapshot() throws IOException {
        synchronized (BalanceData.class) {
            rejectBackendReentry();
            State state = current(this);
            if (state != null) return new Snapshot(state.base.revision(),
                    new LinkedHashMap<>(state.draft.values), true);
            return backendLoad();
        }
    }

    public <T> T transaction(Work<T> work) throws IOException {
        if (work == null) throw new IllegalArgumentException("ARGUMENT");
        synchronized (BalanceData.class) {
            rejectBackendReentry();
            ArrayDeque<State> stack = ACTIVE.get();
            State parent = stack.peek();
            if (parent != null && parent.owner != this) throw new IOException("NESTED_REPOSITORY");
            if (parent != null) {
                try {
                    return work.run(parent.draft);
                } catch (Exception failure) {
                    parent.rollbackOnly = true;
                    throw transactionFailure();
                } catch (Error failure) {
                    parent.rollbackOnly = true;
                    throw failure;
                }
            }

            State state = new State(this, backendLoad());
            stack.push(state);
            try {
                T result;
                try {
                    result = work.run(state.draft);
                } catch (Exception failure) {
                    state.rollbackOnly = true;
                    throw transactionFailure();
                }
                if (state.rollbackOnly) throw new IOException("TRANSACTION_ROLLBACK");
                if (!same(state.base.values, state.draft.values)) {
                    backendCommit(state.base, state.draft.values);
                }
                return result;
            } finally {
                stack.pop();
                if (stack.isEmpty()) ACTIVE.remove();
            }
        }
    }

    private Snapshot backendLoad() throws IOException {
        return backendCall(() -> backend.load());
    }

    private void backendCommit(Snapshot base, Map<String, byte[]> values) throws IOException {
        backendCall(() -> { backend.commit(base, Snapshot.copy(values)); return null; });
    }

    private <T> T backendCall(BackendCall<T> call) throws IOException {
        if (IN_BACKEND.get()) throw new IOException("REENTRANT");
        IN_BACKEND.set(true);
        try {
            return call.run();
        } catch (EncryptedGenerationStore.StoreException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("BACKEND_IO");
        } catch (Exception e) {
            throw new IOException("BACKEND_IO");
        } finally {
            IN_BACKEND.set(false);
        }
    }

    private interface BackendCall<T> { T run() throws Exception; }

    private static State current(FinancialRepository repository) {
        State state = ACTIVE.get().peek();
        return state != null && state.owner == repository ? state : null;
    }

    private static void rejectBackendReentry() throws IOException {
        if (IN_BACKEND.get()) throw new IOException("REENTRANT");
    }

    private static IOException transactionFailure() {
        return new IOException("TRANSACTION_FAILED", new Exception("WORK_FAILED"));
    }

    private static boolean same(Map<String, byte[]> first, Map<String, byte[]> second) {
        if (!first.keySet().equals(second.keySet())) return false;
        for (String key : first.keySet()) if (!Arrays.equals(first.get(key), second.get(key))) return false;
        return true;
    }
}
