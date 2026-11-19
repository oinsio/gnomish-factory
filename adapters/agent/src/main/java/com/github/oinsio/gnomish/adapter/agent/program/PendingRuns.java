package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.executorcontract.Status;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * The runs of one {@code program} executor that returned {@code pending} in one factory run and have
 * not ended (FR23 of define-executor-contract; design D3, D6; design D14 of
 * migrate-verify-to-executor-contract as amended 2026-11-13): one table per {@code program} registry
 * entry per run, shared by every {@link ProgramExecutor} bound from that entry, stage and check alike.
 * An entry is keyed by its subject — a check's identity in the round, none for a stage round — and its
 * pending key, so two checks of one program never share a key, and carries the working-copy view taken
 * before the run's {@code start} when the run is a {@code verdict} or {@code decision}: the view the
 * poll that ends the run is judged against, whichever binding took it. A poll {@linkplain #claim
 * claims} its entry before it spawns, and the entry comes back when the poll settles: under the new key
 * when the poll returns {@code pending} again, and under the key it polled when the poll ends the run, so
 * the ended run's key stays pollable for the rest of the factory run and a re-poll of it reaches the
 * program, which answers the ended run again (design D11 of migrate-verify-to-executor-contract: the
 * conformance kit's re-poll case is driven through this table); a poll that throws leaves it out. A
 * key enters the table from two places only: an operation of a binding of this run that returned {@code
 * pending} ({@link #settled}), and a key the resume path read from the open round's pending record
 * ({@link #adopted}) — never from the request on the wire. Any other key, one an earlier run issued
 * included, is unknown here, and its poll is refused.
 *
 * <p>The class and its constructor are public so the run's assembly can build the table where it
 * builds the entry and hand it on ({@link PendingScope}); every read and write stays in this package.
 *
 * <p>The map is concurrent and nothing blocks while it is read or written ({@code lock-scope.md}, the
 * three-phase shape): the claim is the first phase, the spawn runs with nothing held, and the
 * settling is the third. Because the claim removes the entry, a second poll of the same key while
 * the first is spawning finds nothing and is refused, rather than spawning the program twice.
 *
 * <p>Implements FR23, FR11, NFR-S4 of define-executor-contract; FR3, FR11, FR12 of
 * migrate-verify-to-executor-contract.
 */
public final class PendingRuns {

    /**
     * One pending run.
     *
     * @param before the view of the working copy taken before its {@code start}, or at the adoption
     *     of a recorded key; {@code null} for a run the working-copy check does not apply to, and for
     *     a refused adoption
     * @param refusal what the key's poll returns instead of spawning, for an adoption the working
     *     copy refused ({@link RecordedKeyAdoption}); {@code null} otherwise
     */
    record Entry(
            WorkingCopyView.@Nullable Taken before,
            @Nullable ExecutorResult refusal) {

        /**
         * A run that was started, its answer judged against {@code before}.
         *
         * @param before the view taken before its {@code start}, or {@code null}
         */
        Entry(WorkingCopyView.@Nullable Taken before) {
            this(before, null);
        }
    }

    /** Where an entry lives: its subject, none for a stage round, and its key, which is never missing. */
    private record Slot(@Nullable CheckRef subject, PendingKey key) {

        Slot {
            Objects.requireNonNull(key, "key");
        }
    }

    private final ConcurrentMap<Slot, Entry> runs = new ConcurrentHashMap<>();

    /** An empty table: the run's, built where the run builds the program's registry entry. */
    public PendingRuns() {
        // Nothing to set: the table fills from the run's own results and its adoptions.
    }

    /**
     * Takes the pending run a key names for a subject out of the table, for the one poll that
     * continues it: from now on the key names nothing, until {@link #settled} records the poll's own
     * {@code pending} key.
     *
     * @param subject the check the key was issued for, or {@code null} for a stage round
     * @param key the key a poll carries; never null
     * @return the run, or {@code null} when no operation of this run returned the key for the
     *     subject, or another poll already claimed it
     */
    @Nullable
    Entry claim(@Nullable CheckRef subject, PendingKey key) {
        return runs.remove(new Slot(subject, key));
    }

    /**
     * Enters a key the resume path read from the open round's pending record, unless an operation of
     * this run already returned it for the subject — then the run it started keeps its own entry.
     *
     * @param subject the check the key was recorded for, or {@code null}
     * @param key the recorded key; never null
     * @param entry the run as its adoption found it; never null
     */
    void adopted(@Nullable CheckRef subject, PendingKey key, Entry entry) {
        runs.putIfAbsent(new Slot(subject, key), entry);
    }

    /**
     * Records how an operation of a run settled: a {@code pending} result's key names the run for its
     * subject from now on; any other result of a poll ends the run, and the key that poll carried keeps
     * naming it, so a re-poll of the ended run's key spawns the program again (design D11 of
     * migrate-verify-to-executor-contract); a {@code start} that did not go pending issued no key.
     *
     * @param subject the check the run is for, or {@code null}
     * @param polled the key the settling poll carried, or {@code null} for a {@code start}
     * @param entry the run; never null
     * @param result the operation's result; never null
     */
    void settled(@Nullable CheckRef subject, @Nullable PendingKey polled, Entry entry, ExecutorResult result) {
        PendingKey naming =
                result.status() == Status.PENDING ? Objects.requireNonNull(result.pendingKey(), "pendingKey") : polled;
        if (naming != null) {
            runs.put(new Slot(subject, naming), entry);
        }
    }
}
