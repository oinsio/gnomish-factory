package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import org.jspecify.annotations.Nullable;

/**
 * The container-mode {@link ResumedBranch} (FR1 of add-serve-sandbox-lifecycle): the handoff bundle
 * {@link TakeContainerResumeBootstrap#bootstrap} produces once a resumed task's branch is located
 * (no worktree — the read is over bare git objects, {@link SandboxRunSupport#readTaskJson}) and its
 * {@code task.json}/{@code state.json} loaded, for {@link TakeDispositionResume}'s shared routing
 * table to switch on {@code outcome} without re-deriving any of it. Its host twin is {@link
 * ResumeBootstrap}; what the two add beyond {@link ResumedBranch} — a sandbox bundle here, a
 * worktree there — is reached only through {@link ContainerResumeMechanics}.
 *
 * @param taskId the tracker's original (un-sanitized) taskId, as supplied to {@code take <ref>}
 * @param context the resumed task's identity, description and decisions, read from {@code
 *     task.json}
 * @param outcome the task's recorded outcome at the DTO level, or {@code null} if the last visit
 *     ended without recording one (process death)
 * @param lastEscalation the last escalation report, or {@code null} if the task was never
 *     escalated
 * @param support the sandbox run support bound to this task's branch; never null
 * @param branchName the task branch's short name, e.g. {@code gnomish/PROJ-1}
 * @param trackerWritePending {@code true} when the branch's recorded terminal park still has an
 *     unconfirmed tracker write (FR10, D10 of add-claim-heartbeat) — read-only here: container
 *     mode's factory-side task repository has no {@code confirmTerminalWrite} yet, so a container
 *     resume always re-delivers the park as orphaned rather than distinguishing a settled one
 *     (safe, idempotent, just not the fast path host mode gets — see {@link
 *     TakeContainerEngineExecution}'s class javadoc for the identical tradeoff on the fresh path)
 */
record ContainerResumeBootstrap(
        String taskId,
        TaskContext context,
        @Nullable RecordedOutcome outcome,
        @Nullable EscalationReport lastEscalation,
        SandboxRunSupport support,
        String branchName,
        boolean trackerWritePending)
        implements ResumedBranch {}
