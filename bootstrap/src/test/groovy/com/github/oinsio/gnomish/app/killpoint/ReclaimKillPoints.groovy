package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.WorktreeSalvage
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * The two reclaim cycles as kill-point table rows (FR6, NFR-R1 of fix-claim-epoch-fence): a tip
 * written and stamped by a tenure that has ENDED, picked up by a different instance holding a live
 * tenure of its own.
 *
 * <p>These rows enumerate no new kill window. The windows are the ones the park and round
 * transitions already own; what is new is the reader — the pickup runs as a SECOND instance, whose
 * book holds {@link #RECLAIM_EPOCH} rather than the epoch on the tip. That is exactly the situation
 * the removed read-side epoch comparison gave its own shape, quarantining every legitimate reclaim
 * in production, and it is invisible to any spec whose branch carries no stamp at all — so
 * each row asserts first that the tip really is stamped by the ended tenure, then that the
 * reclaiming instance classifies it by content and converges it (`testing.md`, adversarial
 * fixtures).
 *
 * <p>The reclaim pickup writes nothing, by design: routing a branch on its shape costs no commit,
 * push, fetch, or tracker write (NFR-R2, NFR-C1), so the harness's "second pass changes nothing"
 * assertion is here the assertion that a reclaim is free — not merely idempotent.
 *
 * <p>Host medium only. Both rows need a tip the medium's own writers stamped, and the {@code
 * InProgress} one needs a recorded round, which is written through the host attempt persistence in
 * a materialized worktree; the container twin writes its rounds inside a box, which this daemon-free
 * matrix does not start (its half of the pair is covered by {@code ContainerResume*}).
 *
 * <p>Implements FR6, NFR-R1 of fix-claim-epoch-fence.
 */
final class ReclaimKillPoints {

    /**
     * The tenure the reclaiming instance holds — deliberately far from any epoch the in-memory
     * tracker issues the world, so "the reader's epoch differs from the tip's" is true by
     * construction rather than by luck.
     */
    static final ClaimEpoch RECLAIM_EPOCH = new ClaimEpoch(9_000L)

    private ReclaimKillPoints() {}

    /**
     * The escalated tip an operator returns to ready: the ended tenure parks, its receipt lands, and
     * the next instance to claim the task reclaims the branch (spec scenario "Escalated, returned,
     * reclaimed").
     *
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in that medium
     */
    static KillPointTransition parkedTransition(String medium, Closure world) {
        new KillPointTransition(
                name: "${medium} reclaim of a parked tip",
                steps: [
                    "the ended tenure's escalation outcome commit",
                    "the ended tenure's receipt clearing the pending marker",
                ],
                world: world,
                step: { KillPointWorld w, int index -> parkStep(w, index) },
                shape: { KillPointWorld w -> reclaimingShape(w) },
                pickup: { KillPointWorld w -> reclaim(w) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                invariant: { KillPointWorld w ->
                    assertStampedByEndedTenure(w)
                },
                frozenShapes: ['Parked', 'Parked'],
                converged: 'Parked')
    }

    /**
     * The tip a crashed instance leaves behind: one recorded round, then the salvage commit that
     * turns its interrupted work into the branch tip, both stamped with the dead tenure's epoch. The
     * reaper returns the claim and the next instance reclaims (spec scenario "Crashed, reaped,
     * reclaimed").
     *
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a freshly created, claimed task in the HOST medium (it needs a worktree)
     */
    static KillPointTransition salvagedTransition(String medium, Closure world) {
        new KillPointTransition(
                name: "${medium} reclaim of a salvaged tip",
                steps: [
                    "the ended tenure's recorded round",
                    'the salvage commit that froze its interrupted work',
                ],
                world: world,
                step: { KillPointWorld w, int index -> salvageStep(w, index) },
                shape: { KillPointWorld w -> reclaimingShape(w) },
                pickup: { KillPointWorld w -> reclaim(w) },
                fingerprint: { KillPointWorld w -> w.fingerprint() },
                invariant: { KillPointWorld w ->
                    assertStampedByEndedTenure(w)
                },
                frozenShapes: ['InProgress', 'InProgress'],
                converged: 'InProgress')
    }

    private static void parkStep(KillPointWorld world, int index) {
        if (index == 0) {
            world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(
                            TaskState.atStageStart('build'), new EscalationReport.AttemptsExhausted(3)))
        } else {
            world.store.confirmTerminalWrite(world.taskId)
        }
    }

    private static void salvageStep(KillPointWorld world, int index) {
        def runner = new GitProcessRunner()
        if (index == 0) {
            new GitAttemptPersistence(runner, world.worktree, world.taskId, world.epochs).persist(
                    world.taskId,
                    TaskState.atStageStart('build').recordUnburnedRound(new AttemptRecord(
                            0, AttemptRecord.Result.PASSED, Instant.parse('2026-07-18T09:00:00Z'), [],
                            ExecutorUsage.none(), JudgeUsage.none(), [])),
                    new ToolTrace(
                            new AttemptKey(world.taskId, 'build', 0),
                            [
                                new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                            ]))
        } else {
            Files.writeString(world.worktree.resolve('half-done.txt'), 'interrupted work\n')
            new WorktreeSalvage(runner, world.worktree, world.epochs).salvage(world.taskId)
        }
    }

    /**
     * The premise every row rests on, asserted rather than assumed: the tip carries the epoch the
     * world's own tenure was issued. An unstamped tip would make the rows pass for the wrong reason,
     * since a reader can hardly mistake a missing trailer for a superseded one.
     */
    private static void assertStampedByEndedTenure(KillPointWorld world) {
        def stamped = world.tipEpoch()
        assert stamped != null: "${world.taskId}: the tip carries no claim-epoch trailer, so no reclaim is under test"
        assert stamped == world.epochs.epochFor(world.taskId).orElse(null):
        "${world.taskId}: the tip is stamped by a tenure this world never held"
        assert stamped != RECLAIM_EPOCH: "${world.taskId}: the reclaiming instance holds the tip's own epoch"
    }

    /**
     * What the reclaiming instance sees: the production classifier, reached through a branch reader
     * built over that instance's own tenure record. The epoch it holds is not the tip's, which is
     * the whole premise — and since FR1 the classification does not depend on either.
     */
    private static String reclaimingShape(KillPointWorld world) {
        reclaimingBranches(world).classifyShape(world.repoDir, world.taskId).label()
    }

    /**
     * The reclaim itself: the next instance classifies the branch it just claimed and finds a shape
     * it can route. A quarantine shape here is the production defect this change removes — every
     * reclaim of a stamped tip taking the non-recoverable exit — so the row names it directly rather
     * than only comparing labels.
     */
    private static void reclaim(KillPointWorld world) {
        def shape = reclaimingBranches(world).classifyShape(world.repoDir, world.taskId)
        assert !(shape instanceof BranchShape.Bare
        || shape instanceof BranchShape.Unknown
        || shape instanceof BranchShape.Corrupt
        || shape instanceof BranchShape.UnsupportedVersion):
        "${world.taskId}: the reclaiming instance quarantined a tip written by an ended tenure (${shape.label()})"
    }

    private static GitTaskBranches reclaimingBranches(KillPointWorld world) {
        def reclaimerEpochs = new ClaimEpochBook()
        reclaimerEpochs.issued(world.taskId, RECLAIM_EPOCH)
        new GitTaskBranches(new GitProcessRunner(), reclaimerEpochs)
    }
}
