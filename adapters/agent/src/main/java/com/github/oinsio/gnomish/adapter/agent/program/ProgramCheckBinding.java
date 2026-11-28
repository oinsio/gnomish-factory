package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.describe.ExecutorEgress;
import com.github.oinsio.gnomish.app.port.check.BoundCheck;
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource;
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource;
import com.github.oinsio.gnomish.domain.engine.CheckRef;
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * The check binding of one law-declared {@code program} executor, through a fresh box per request
 * (design D6, D11 as amended 2026-11-14, D14 as amended 2026-11-13, D18 of
 * migrate-verify-to-executor-contract): each request of the check — its {@code start}, or a poll
 * with the adoption a recorded key takes first — runs a {@link ProgramExecutor} in a box
 * materialized for that request at the round's attempt commit, read once from the check's
 * attempt-commit workspace, and released by the check round when the request ends. The box's guard
 * admits the operator's sandbox egress and the hosts the law grants this executor ({@link
 * ExecutorEgress#of}); its credentials are the program's own grant, the only credential layer it
 * receives ({@link ProgramSpawn}). The executor is bound behind {@link GuardedExecutor}, so the box's
 * guard is probed before and after every request (design D13 of extract-agent-round-runner), and the
 * executor's {@code PendingAdoption} role is handed beside the decorator, never through it.
 *
 * <p>The executor shares the run's table of pending runs of the program — built with its registry
 * entry, the same table its stage rounds use — scoped to the request's check ({@link CheckRef}), and
 * an adoption expects {@code HEAD} on the very commit the box was put on ({@link PendingScope}). So a
 * {@code start}'s key and its before-view outlive the box they were taken in, and every request of
 * the check, a resumed one included, stands on one commit in a box of its own.
 *
 * <p>A box that cannot be materialized, or a working copy with no attempt commit, binds an executor
 * that answers every operation {@code failed/infrastructure} naming why, with no process spawned —
 * a cannot-verify the operator sees, never an exception out of the check round.
 *
 * <p>Implements FR1, FR3, FR11, NFR-S1 of migrate-verify-to-executor-contract; FR15 of
 * extract-agent-round-runner.
 */
public final class ProgramCheckBinding {

    private final DescribedProgram program;
    private final ProgramRoundEquipment equipment;
    private final PendingRuns runs;
    private final FreshBoxSource boxes;
    private final List<String> egress;

    /**
     * @param program the executor as the describe preflight left it; never null
     * @param equipment the run's program equipment; never null
     * @param runs the run's table of pending runs of the program, built with its registry entry;
     *     never null
     * @param boxes the run's fresh boxes; never null
     */
    public ProgramCheckBinding(
            DescribedProgram program, ProgramRoundEquipment equipment, PendingRuns runs, FreshBoxSource boxes) {
        this.program = program;
        this.equipment = equipment;
        this.runs = runs;
        this.boxes = boxes;
        this.egress = ExecutorEgress.of(program.declaration());
    }

    /**
     * The executor of one request of the check {@code ref}, in a box of its own.
     *
     * @param ref the check's identity in the round, which the run's table is scoped to; never null
     * @param workspace the check's working copy: the round's attempt-commit workspace; never null
     * @return the executor and the release of its box; never null
     */
    public BoundCheck bind(CheckRef ref, Workspace workspace) {
        if (!(workspace instanceof AttemptCommitWorkspace attempt)) {
            return refused("a program verdict check runs in a fresh box at the attempt commit, and the"
                    + " working copy names none (" + workspace.getClass().getSimpleName() + ")");
        }
        String commit = attempt.attemptCommitSha();
        CheckEnvironmentSource.Acquired box;
        try {
            box = boxes.freshBox(commit, egress);
        } catch (RuntimeException e) {
            // The source disposed what it built; the failure is this request's answer, reported by
            // the check round's outcome — one failure, one log, at the engine's decision.
            return refused("its fresh box at " + commit + " could not be materialized: "
                    + UntrustedText.subprocess(String.valueOf(e.getMessage())).excerpt(512));
        }
        var executor = new ProgramExecutor(
                program.declaration(),
                program.describe(),
                program.credentials(),
                box.environment(),
                equipment,
                new PendingScope(runs, ref, commit));
        // The box's guard is probed around every request by its one owner (design D13 of
        // extract-agent-round-runner): a dead guard is a cannot-verify, whatever the program answered.
        // The adoption role is the program executor's own, handed beside the decorator, not through it.
        return new BoundCheck(
                GuardedExecutor.over(
                        ProgramFailures.Subject.PROGRAM, program.declaration().name(), box.environment(), executor),
                executor,
                box::close);
    }

    private BoundCheck refused(String cause) {
        return BoundCheck.unboxed(new UnboxedProgram(
                program.describe(),
                ProgramFailures.infrastructure(
                        ProgramFailures.Subject.PROGRAM, program.declaration().name(), cause, emptyTail())));
    }

    private static com.github.oinsio.gnomish.untrustedtext.UntrustedText emptyTail() {
        return com.github.oinsio.gnomish.untrustedtext.UntrustedText.factory("");
    }
}
