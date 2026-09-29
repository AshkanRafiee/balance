package com.ashkanrafiee.balance;

import android.content.Context;

import java.io.IOException;

/**
 * The single repository boundary for the financial store.
 *
 * <p>This class is deliberately not installed into existing UI callers yet. Once the authority
 * cutover begins, callers obtain one authority and share its adapter and operations instead of
 * opening independent stores or mixing generation and legacy preferences.</p>
 */
final class FinancialAuthority {
    private final FinancialRepository repository;
    private final FinancialSnapshotAdapter snapshots;
    private final FinancialOperations operations;

    private FinancialAuthority(FinancialRepository repository) {
        if (repository == null) throw new IllegalArgumentException("ARGUMENT");
        this.repository = repository;
        this.snapshots = new FinancialSnapshotAdapter(repository);
        this.operations = new FinancialOperations(repository);
    }

    /** Opens the canonical store and migrates legacy financial data if required. */
    static FinancialAuthority open(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("ARGUMENT");
        return fromStore(FinancialStoreProvider.open(context));
    }

    /** Package-level constructor for adapters and tests using an already selected backend. */
    static FinancialAuthority fromRepository(FinancialRepository repository) {
        return new FinancialAuthority(repository);
    }

    private static FinancialAuthority fromStore(EncryptedGenerationStore store) {
        return new FinancialAuthority(new FinancialRepository(FinancialRepository.generationBackend(store)));
    }

    FinancialRepository repository() { return repository; }
    FinancialSnapshotAdapter snapshots() { return snapshots; }
    FinancialOperations operations() { return operations; }

    /** Reads one repository snapshot; callers should keep related decisions within one transaction. */
    FinancialRepository.Snapshot snapshot() throws IOException { return repository.snapshot(); }

    /** Runs one atomic financial operation against this authority. */
    <T> T transaction(FinancialRepository.Work<T> work) throws IOException {
        return repository.transaction(work);
    }
}
