package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TrackerWrite;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The shared "drive the engine loop to a terminal boundary" tail of both
 * container-mode paths — the fresh run ({@link ContainerGitModeRunner}) and
 * every resumed continuation ({@link ContainerResumeRunner}) — mirroring what
 * {@link GitModeRunner}/{@link GitResumeContinuation} share on the host path:
 * assemble with the sandbox pieces, run {@link RunnerOutcomeLoop}, and settle
 * the terminal boundary per D19 — {@code Completed} disposes the environment
 * before the factory-side outcome and cleanup commits; {@code Aborted} records
 * on the last harvested tip; a park ({@code Escalated}/{@code Paused}) records
 * its outcome and exits 10/11 through {@link RunParkedException} (design D8 of
 * make-run-headless); every non-completed exit leaves the environment stopped
 * with volume and network kept (FR6).
 *
 * <p>Implements FR6, FR21, FR25, D19 of add-sandbox-core; FR1, FR2, FR10 of
 * make-run-headless.
 */
final class ContainerTerminalDrive {

    private ContainerTerminalDrive() {}

    static void run(
            RunAssembly assembly,
            SandboxRunSupport support,
            RunOrder order,
            TaskContext context,
            TaskState state,
            LawBinding lawBinding,
            @Nullable PendingVerification pending) {
        // Runner start prunes objects a dead instance left labelled (FR11, NFR-R2), keeping this
        // task's own environments so a reattaching resume is never swept; no daemon = a no-op.
        support.sweepOrphans();
        // The guard container outlives the process that created it, so a resume onto a surviving
        // one continues the denial delta from the position its last attempt committed instead of
        // replaying the container's whole log onto this round (FR5 of fix-denial-report-attachment).
        support.restoreDenials();
        PipelineDefinition definition = order.definition();
        var assembled = assembly.withSandbox(support.pieces(pending))
                .assemble(order, context, state, support.persistence(), List.of(), lawBinding);

        var returnPath = new TerminalOutcomeRender.ReturnPath(order.cloneDir(), context.taskId());
        TaskOutcome outcome;
        boolean returned = false;
        try {
            outcome = assembled
                    .loop()
                    .run(definition, context, state, support.workspace(), assembled.ports(), returnPath);
            returned = true;
        } catch (AbortedException aborted) {
            TaskOutcome.Aborted abortedOutcome = aborted.outcome();
            if (abortedOutcome != null) {
                support.recordAborted(abortedOutcome);
            }
            throw aborted;
        } finally {
            if (!returned) {
                // Aborted (recorded above) or a failure out of the loop: no gnome process may keep
                // executing; the box is kept stopped for salvage/resume (keep semantics).
                support.keepStopped();
            }
        }

        if (!(outcome instanceof TaskOutcome.Completed)) {
            parkAndKeep(support, outcome);
            throw new RunParkedException(outcome, returnPath);
        }
        support.completeAndDispose(support.readFinalState());
        // A manual run has no tracker to write to, so the completion's destructive last step follows
        // its intent immediately — there is no external effect between them to wait on (FR10 of
        // harden-task-branch-contract).
        support.finishCleanup();
    }

    /**
     * The park arm of the terminal boundary (design D8 of make-run-headless): the park's outcome
     * commit with {@link TrackerWrite#NONE} — a manual run has no tracker write to wait for, so the
     * record carries no pending marker and no receipt commit follows — and then the box stopped and
     * kept for the resume. Constructive before destructive: the stop runs after the record, and runs
     * even when the record fails. Never the {@code Completed} disposal or cleanup, which belong to
     * {@code Completed} alone.
     */
    private static void parkAndKeep(SandboxRunSupport support, TaskOutcome outcome) {
        try {
            support.recordPark(outcome, TrackerWrite.NONE);
        } finally {
            support.keepStopped();
        }
    }
}
