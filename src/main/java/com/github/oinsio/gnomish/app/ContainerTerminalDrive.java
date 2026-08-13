package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
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
 * on the last harvested tip; every non-completed exit leaves the environment
 * stopped with volume and network kept (FR6).
 *
 * <p>Implements FR6, FR21, FR25, D19 of add-sandbox-core.
 */
final class ContainerTerminalDrive {

    private ContainerTerminalDrive() {}

    static void run(
            ManualRunAssembly assembly,
            ContainerRunSupport support,
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            Path cloneDir,
            SnapshotTipCheck.@Nullable InterruptedVerification pending) {
        // Runner start prunes objects a dead instance left labelled (FR11, NFR-R2), keeping this
        // task's own environments so a reattaching resume is never swept; no daemon = a no-op.
        support.sweepOrphans();
        var assembled = assembly.withSandbox(support.pieces(pending))
                .assemble(definition, context, state, interactiveMode, support.persistence(), List.of(), cloneDir);

        boolean completed = false;
        try {
            assembled.loop().run(definition, context, state, support.workspace(), assembled.ports());
            completed = true;
        } catch (AbortedException aborted) {
            TaskOutcome.Aborted outcome = aborted.outcome();
            if (outcome != null) {
                support.recordAborted(outcome);
            }
            throw aborted;
        } finally {
            if (!completed) {
                // Aborted (recorded above) or an EOF-interrupted dialog: no gnome process may
                // keep executing; the box is kept stopped for salvage/resume (keep semantics).
                support.keepStopped();
            }
        }

        support.completeAndDispose(support.readFinalState());
    }
}
