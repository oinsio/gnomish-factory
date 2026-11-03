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
 * ends the run is judged against. A poll {@linkplain #claim claims} its entry before it spawns, and
 * the entry comes back under the new key only when the poll returns {@code pending} again; a poll
 * that ends the run, or that throws, leaves it out. Pending keys are not persisted in this change
 * (proposal NG7): a key another instance issued is unknown here, and its poll is refused.
 *
 * <p>The map is concurrent and nothing blocks while it is read or written ({@code lock-scope.md}, the
 * three-phase shape): the claim is the first phase, the spawn runs with nothing held, and the
 * settling is the third. Because the claim removes the entry, a second poll of the same key while
 * the first is spawning finds nothing and is refused, rather than spawning the program twice.
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
     * Takes the pending run a key names out of the map, for the one poll that continues it: from now
     * on the key names nothing, until {@link #settled} records the poll's own {@code pending} key.
     *
     * @param key the key a poll carries; never null
     * @return the run, or {@code null} when no operation of this instance returned the key, or another
     *     poll already claimed it
     */
    @Nullable
    Entry claim(PendingKey key) {
        return runs.remove(key);
    }

    /**
     * Records how an operation of a run settled: a {@code pending} result's key names the run from
     * now on; any other result leaves the run out, since it has ended.
     *
     * @param entry the run; never null
     * @param result the operation's result; never null
     */
    void settled(Entry entry, ExecutorResult result) {
        if (result.status() == Status.PENDING) {
            runs.put(Objects.requireNonNull(result.pendingKey(), "pendingKey"), entry);
        }
    }
}
