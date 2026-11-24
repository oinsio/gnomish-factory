package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.git.CurrentRound;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one snapshot step of a round in a branch mode (design D22 of
 * migrate-verify-to-executor-contract): the round's gnome work is closed by a snapshot commit before
 * the engine verifies it, on the box and on the host alike, through a {@link SnapshotMedium}. The
 * step owns what the two media share: the subject, chosen by the round's {@link RoundClose} ({@link
 * #subject}); the order — the medium's boundary verdict first, the commit only after a clean one;
 * and the one {@link CurrentRound#snapshotted} transition, so no second copy of either lives per
 * medium (rule of two: both media are in this module, no classpath barrier allows a declared pair).
 *
 * <p>A verdict other than clean declines the snapshot: nothing is restored, committed or thrown, and
 * the cell keeps the open round without a snapshot. The round's persist then judges the same working
 * copy from the tip the round opened on, reports the violation and aborts the task — the place the
 * engine turns a boundary into {@code Aborted}, rather than into an executor that could not run.
 *
 * <p>Kill windows: the step's one durable act is the medium's ref transaction. A kill before it
 * leaves the register on the previous closing commit — the shape "during the agent", whose owner is
 * the ordinary resume; a kill after it leaves the snapshot registered — an interrupted verification
 * (or, under the unverified subject, "during the agent" again), whose owners are named by the
 * {@code lifecycle/task-branch-contract} capability. The cell is written after the transaction, and
 * dies with the process.
 *
 * <p>Implements FR19, NFR-R1 of migrate-verify-to-executor-contract; FR21 of add-sandbox-core.
 */
final class RoundSnapshot {

    private static final Logger log = LoggerFactory.getLogger(RoundSnapshot.class);

    private final SnapshotMedium medium;
    private final CurrentRound rounds;

    /**
     * @param medium where the snapshot is committed; never null
     * @param rounds the run's round cell: the subject names the token the round opened with, and the
     *     snapshot closes that round in it; never null
     */
    RoundSnapshot(SnapshotMedium medium, CurrentRound rounds) {
        this.medium = medium;
        this.rounds = rounds;
    }

    /**
     * Snapshots the open round.
     *
     * @param taskId the task whose round is closing
     * @param stage the current stage id
     * @param round the round's number
     * @param close whether the round's executor completed: the subject's choice
     * @return the snapshot commit, or empty when the medium's verdict declined it
     * @throws IllegalStateException if no round opened — a snapshot names the round that owns it
     * @throws GitPersistFailedException if the medium could not commit
     */
    Optional<String> take(String taskId, String stage, int round, RoundClose close) {
        RoundToken token = rounds.opened();
        AttemptKey key = new AttemptKey(taskId, stage, round);
        var order = new SnapshotOrder(key, token, subject(key, token, close));
        RangeVerdict verdict = medium.judge(order);
        if (!(verdict instanceof RangeVerdict.Clean)) {
            // Not the verdict: the round's persist judges the same tree and reports it (one fault,
            // one log at the layer that decides).
            log.debug("the snapshot of round {} is declined; its boundary: {}", key, verdict);
            return Optional.empty();
        }
        String snapshot = medium.commit(order);
        rounds.snapshotted(snapshot, close);
        return Optional.of(snapshot);
    }

    /**
     * The one choice of a snapshot's subject by the round's close: {@code gnomish: snapshot}, which
     * a pickup resumes as an interrupted verification, or {@code gnomish: unverified-snapshot} or
     * {@code gnomish: pending-snapshot} (the snapshot a pending round commit lands on, design D11 of
     * extract-agent-round-runner), which it never does. A persist that re-admits the snapshot judges
     * it by the same choice.
     */
    static String subject(AttemptKey key, RoundToken token, RoundClose close) {
        return switch (close) {
            case VERIFIABLE -> ServiceCommitMessages.snapshot(key.stage(), key.attempt(), token);
            case UNVERIFIABLE -> ServiceCommitMessages.unverifiedSnapshot(key.stage(), key.attempt(), token);
            case PENDING -> ServiceCommitMessages.pendingSnapshot(key.stage(), key.attempt(), token);
        };
    }
}
