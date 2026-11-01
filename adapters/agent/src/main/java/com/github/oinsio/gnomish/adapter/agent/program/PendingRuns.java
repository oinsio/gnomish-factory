package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.PendingKey;
import com.github.oinsio.gnomish.executorcontract.Status;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * The runs of one {@link ProgramExecutor} instance that returned {@code pending} and have not ended
 * (FR23 of define-executor-contract; design D3, D6): one entry per pending key the instance received
 * from a {@code start} or a {@code poll} of its own, carrying the working-copy view taken before the
 * run's {@code start} when the run is a {@code verdict} or {@code decision} — the view the poll that
 * ends the run is judged against. An entry is moved to the new key when a poll returns {@code
 * pending} again, and removed at the poll that ends the run. Pending keys are not persisted in this
 * change (proposal NG7): a key another instance issued is unknown here, and its poll is refused.
 *
 * <p>The map is concurrent and nothing blocks while it is read or written ({@code lock-scope.md}): a
 * spawn runs between the lookup and the settling, with nothing held.
 *
 * <p>Implements FR23, FR11, NFR-S4 of define-executor-contract.
 */
final class PendingRuns {

    /**
     * One pending run.
     *
     * @param before the view of the working copy taken before its {@code start}, {@code null} for a
     *     run the working-copy check does not apply to
     */
    record Entry(WorkingCopyView.@Nullable Taken before) {}

    private final ConcurrentMap<PendingKey, Entry> runs = new ConcurrentHashMap<>();

    /**
     * The pending run a key names.
     *
     * @param key the key a poll carries; never null
     * @return the run, or {@code null} when no operation of this instance returned the key
     */
    @Nullable
    Entry of(PendingKey key) {
        return runs.get(key);
    }

    /**
     * Records how an operation of a run settled: the key it was polled with, if any, no longer names
     * the run, and a {@code pending} result's key names it from now on.
     *
     * @param polled the key the operation was a poll of, {@code null} for a start
     * @param entry the run; never null
     * @param result the operation's result; never null
     */
    void settled(@Nullable PendingKey polled, Entry entry, ExecutorResult result) {
        if (polled != null) {
            runs.remove(polled);
        }
        if (result.status() == Status.PENDING) {
            runs.put(Objects.requireNonNull(result.pendingKey(), "pendingKey"), entry);
        }
    }
}
