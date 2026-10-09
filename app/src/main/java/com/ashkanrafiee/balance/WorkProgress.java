package com.ashkanrafiee.balance;

/**
 * Progress callbacks for long stores, scans, backups and exports. Implementations must be safe to
 * call from any worker thread; UI implementations post to the main thread themselves. Every call
 * is advisory: a stage may be skipped for tiny inputs and fractions are approximate, but a call
 * that returns normally must never report a completion that did not happen.
 */
interface WorkProgress {
    /** Names the current phase with a string resource. Implies indeterminate progress. */
    void stage(int stageResId);

    /** Reports {@code done} of {@code total} rows or bytes. A {@code total} below 1 means the
     *  total is unknown and the bar stays indeterminate. */
    void progress(long done, long total);
}
