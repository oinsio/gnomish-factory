package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The end of a manual {@code run --resume} of a completed task, on both media (FR4 of
 * fix-terminal-receipt-convergence; FR8, UX2 of add-git-workflow): the one printer of the done
 * report, and the {@code Delivered} route that feeds it from history.
 *
 * <p>Two shapes record a completed task. A {@code CompletedUncleaned} tip still carries its
 * envelope, so the resume reads it there, lands the owed cleanup and prints ({@link
 * GitResumeContinuation#reportCompleted}, {@link ContainerResumeOutcomes#reportCompleted}). A
 * {@code Delivered} tip carries none — the cleanup commit removed it — so {@link #readDelivered}
 * reads the context and final state from the {@code Completed} commit through {@link
 * TaskBranchGit#readDelivered}, the same history reader take's {@code Delivered} route uses, and
 * the resume prints the same report and writes nothing. Both media decide the shape through the
 * branch classifier at the registered commit ({@link TaskBranchGit#classifyShape}); the report
 * text is this class's, so the two shapes and the two media cannot word the end of a completed
 * task differently.
 *
 * <p>Implements FR4 of fix-terminal-receipt-convergence; FR8, UX2 of add-git-workflow; FR9 of
 * make-run-headless.
 */
final class CompletedResume {

    private CompletedResume() {}

    /**
     * Reads a delivered task's pre-cleanup record from history and refuses a {@code --decision}
     * over it, as over any completed outcome (FR9 of make-run-headless). Reads only.
     *
     * @param branches the branch port the history is read through; never null
     * @param cloneDir the clone holding the task branch; never null
     * @param taskId the {@code --resume} task id, as the operator supplied it
     * @param decision the operator's {@code --decision}, or {@code null} when none was given
     * @return the delivered context and final state; never null
     * @throws UsageException when {@code decision} is present
     */
    static DeliveredBranchState readDelivered(
            TaskBranchGit branches, Path cloneDir, String taskId, @Nullable String decision) {
        DeliveredBranchState delivered = branches.readDelivered(cloneDir, taskId);
        ResumeDecisionGuard.requireEscalatedFor(
                delivered.context().taskId(),
                new RecordedOutcome.Completed(),
                delivered.finalState().position(),
                decision);
        return delivered;
    }

    /**
     * Prints the completed task's status summary on the run's console owner (FR5, FR6 of
     * harden-untrusted-text-sinks: the summary carries the task's own stage names and decision
     * text), exactly as a live run's stop renders (UX2).
     *
     * @param assembly the run's assembly, whose console owner prints; never null
     * @param context the task's recorded context; never null
     * @param finalState the task's last recorded state; never null
     */
    static void print(RunAssembly assembly, TaskContext context, TaskState finalState) {
        var report = StatusReport.build(context, finalState, null, null);
        assembly.dialogConsole().print(new StatusTextRenderer().renderFull(report) + ConsoleIO.LINE_END);
    }
}
