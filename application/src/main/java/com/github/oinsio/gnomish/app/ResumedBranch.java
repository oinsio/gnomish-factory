package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import org.jspecify.annotations.Nullable;

/**
 * The execution-mode-independent view of a resumed task branch: everything {@link
 * TakeDispositionResume}'s routing table decides on, and nothing else. Host mode's {@link
 * ResumeBootstrap} adds a materialized worktree and base commit; container mode's {@link
 * ContainerResumeBootstrap} adds a {@link com.github.oinsio.gnomish.app.port.run.SandboxRunSupport}
 * bundle — neither belongs to the routing decision, so neither appears here (design D8 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>This interface is what lets ONE routing table serve both modes. Before it, each mode carried
 * its own mirrored table and they drifted: the container copy never grew the {@code Completed}
 * deferred-finish branch its host twin had.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, D3 of add-tracker-port.
 */
public sealed interface ResumedBranch permits ResumeBootstrap, ContainerResumeBootstrap {

    /** The tracker's original (un-sanitized) taskId, as supplied to {@code take <ref>}. */
    String taskId();

    /** The resumed task's identity, description and decisions, read from {@code task.json}. */
    TaskContext context();

    /**
     * The task's recorded outcome at the DTO level, or {@code null} when the last visit ended
     * without recording one (process death).
     */
    @Nullable
    RecordedOutcome outcome();

    /** The last escalation report, or {@code null} when the task was never escalated. */
    @Nullable
    EscalationReport lastEscalation();

    /** The task branch's short name, e.g. {@code gnomish/PROJ-1}. */
    String branchName();

    /**
     * {@code true} when the branch's recorded terminal park still has an unconfirmed tracker write
     * (FR10, D10 of add-claim-heartbeat) — the marker distinguishing an orphaned park to reconcile
     * from a settled one to resume normally.
     */
    boolean trackerWritePending();
}
