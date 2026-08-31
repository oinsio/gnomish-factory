package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.CommitIdentity;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.StaleTipException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The sandboxed-mode realization of {@link TaskRepository} (design D19): the same four lifecycle
 * write points as {@link GitTaskRepository} — branch creation with {@code task.json}, the resume
 * decision, the terminal {@link TaskOutcome}, and the {@code Completed} cleanup — but created
 * factory-side as plumbing commits over bare git objects through {@link GitObjects}, with no working
 * copy, no checkout, and no hook execution (FR17, FR25). This is the write-side twin of the
 * bare-object reads {@link BranchStateReader}/{@link DeliveredBranchReader} already perform.
 *
 * <p>The in-box channel is unavailable exactly when these writes matter (D19): at creation no
 * environment exists yet, at abort the box is dead or quarantined, and at cleanup it is already
 * disposed. Each write therefore reads the current tip as a bare object, applies its edits over that
 * tip's tree, and advances the branch ref with git's atomic compare-and-swap — a tip moved by a
 * concurrent factory instance fails the write ({@link StaleTipException}) rather than being
 * force-overwritten, so no existing commit is ever lost. {@code task.json} never crosses the
 * environment channel, so it needs no harvest-time read-back (D16).
 *
 * <p>Ordering consequences of the bare-object approach (D19): an {@code Aborted} outcome commits on
 * the last harvested tip while the violating environment is left untouched as evidence, and the
 * {@code Completed} outcome and cleanup commits are created after the environment is disposed — the
 * last in-box commit was the state commit (D15), so no live environment is required here. Host mode
 * keeps {@link GitTaskRepository}'s worktree commits unchanged (G4, D20).
 *
 * <p>Strict port: any failure to durably record a lifecycle event is thrown as {@link
 * GitTaskRepositoryException}, matching {@link GitTaskRepository}. Implements FR25 of
 * add-sandbox-core.
 */
public final class GitObjectsTaskRepository implements TaskLifecycleStore {

    private static final String REF_PREFIX = "refs/heads/";

    /** The factory identity that authors lifecycle commits when none is supplied (design D19). */
    private static final CommitIdentity DEFAULT_IDENTITY =
            new CommitIdentity("gnomish-factory", "gnomish-factory@localhost");

    private final GitObjects gitObjects;
    private final CommitIdentity identity;
    private final Clock clock;
    private final ClaimEpochSource epochs;

    /**
     * @param gitObjects the bare-object facade opened against the factory clone (git dir + a
     *     factory-private temp dir for indexes)
     * @param epochs the tenure every lifecycle commit is stamped with (FR13 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is held
     */
    public GitObjectsTaskRepository(GitObjects gitObjects, ClaimEpochSource epochs) {
        this(gitObjects, DEFAULT_IDENTITY, Clock.systemUTC(), epochs);
    }

    /**
     * @param gitObjects the bare-object facade opened against the factory clone
     * @param identity the name/email git records as author and committer of lifecycle commits
     * @param clock the source of commit timestamps and {@code createdAt} — injectable so specs pin
     *     deterministic commit ids (design D19)
     * @param epochs the tenure every lifecycle commit is stamped with (FR13)
     */
    public GitObjectsTaskRepository(
            GitObjects gitObjects, CommitIdentity identity, Clock clock, ClaimEpochSource epochs) {
        this.gitObjects = gitObjects;
        this.identity = identity;
        this.clock = clock;
        this.epochs = epochs;
    }

    @Override
    public void createTask(TaskContext context, String baseRef, TaskState initialState) {
        String taskId = context.taskId();
        String ref = refFor(taskId);
        if (gitObjects.resolveRef(ref).isPresent()) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.STARTED, "creating branch", "branch \"" + ref + "\" already exists");
        }
        ObjectId base = gitObjects
                .resolveRef(baseRef)
                .orElseThrow(() -> new GitTaskRepositoryException(
                        taskId,
                        TaskLifecycleEvent.STARTED,
                        "creating branch",
                        "base ref \"" + baseRef + "\" did not resolve"));

        Instant now = Instant.now(clock);
        var writer = new TaskLifecycleCommitWriter(gitObjects, identity, now, epochs);
        TaskJsonDto dto = TaskJsonMapper.toDto(context, base.hex(), now, null, null, false);
        writer.commit(
                taskId, ref, true, base, writer.putTaskAndState(taskId, dto, initialState), TaskLifecycleEvent.STARTED);
    }

    @Override
    public void appendDecision(String taskId, Decision decision, TaskState resetState) {
        String ref = refFor(taskId);
        var writer = new TaskLifecycleCommitWriter(gitObjects, identity, Instant.now(clock), epochs);
        ObjectId tip = writer.requireTip(taskId, ref, TaskLifecycleEvent.RESUMED);
        TaskRecord current = writer.readCurrent(taskId, tip, TaskLifecycleEvent.RESUMED);

        List<Decision> decisions = new ArrayList<>(current.context().decisions());
        decisions.add(decision);
        TaskContext updated = new TaskContext(
                current.context().taskId(),
                current.context().title(),
                current.context().body(),
                decisions);

        // Appending the resume decision resets outcome to null in the same commit (FR5/D9 contract).
        TaskJsonDto dto = TaskJsonMapper.toDto(
                updated, current.baseCommit(), current.createdAt(), null, current.lastEscalation(), false);
        // One transition, one commit (FR4): the decision and its attempt-counter reset are two
        // tree edits of a single bare-object commit, never two tips.
        writer.commit(
                taskId, ref, false, tip, writer.putTaskAndState(taskId, dto, resetState), TaskLifecycleEvent.RESUMED);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        TaskLifecycleEvent event = eventFor(outcome);
        String ref = refFor(taskId);
        var writer = new TaskLifecycleCommitWriter(gitObjects, identity, Instant.now(clock), epochs);
        ObjectId tip = writer.requireTip(taskId, ref, event);
        TaskRecord current = writer.readCurrent(taskId, tip, event);

        EscalationReport lastEscalation =
                outcome instanceof TaskOutcome.Escalated escalated ? escalated.report() : current.lastEscalation();
        // The durable "terminal write pending" marker, exactly as GitTaskRepository sets it (FR10,
        // D10 of add-claim-heartbeat; FR10 of harden-task-branch-contract): every terminal outcome
        // whose external effect is still owed carries it, and this commit is the durable intent the
        // tracker write follows. Aborted's tracker write is best-effort and carries no marker.
        boolean pending = !(outcome instanceof TaskOutcome.Aborted);
        TaskJsonDto dto = TaskJsonMapper.toDto(
                current.context(), current.baseCommit(), current.createdAt(), outcome, lastEscalation, pending);
        writer.commit(taskId, ref, false, tip, writer.putTaskJson(taskId, dto), event);
    }

    /**
     * Clears the durable "terminal write pending" marker once the outcome's tracker write has
     * confirmed (FR10 of harden-task-branch-contract) — the receipt a later pickup reads instead of
     * re-driving the transition. The bare-object twin of {@link
     * GitTaskRepository#confirmTerminalWrite}: same marker, same meaning, built factory-side with no
     * environment involved, so a container park settles exactly as a host park does.
     *
     * @param taskId the task whose pending marker is cleared; never blank
     */
    @Override
    public void confirmTerminalWrite(String taskId) {
        GitObjectsTerminalCommits.clearPending(gitObjects, writerFor(), taskId, refFor(taskId));
    }

    /**
     * The {@code Completed} cleanup commit — the destructive last step of the completion sequence,
     * run only behind the confirmed tracker finish (FR10 of harden-task-branch-contract). It removes
     * {@code .gnomish-task/} from the tip; prior commits stay reachable as the audit trail (FR15,
     * M4). No live environment is required: the state commit was the last in-box commit (D15, D19),
     * so the box may already be disposed.
     *
     * @param taskId the completed task whose envelope is removed from the branch tip; never blank
     */
    @Override
    public void finishCleanup(String taskId) {
        GitObjectsTerminalCommits.cleanUp(gitObjects, writerFor(), taskId, refFor(taskId));
    }

    private TaskLifecycleCommitWriter writerFor() {
        return new TaskLifecycleCommitWriter(gitObjects, identity, Instant.now(clock), epochs);
    }

    private static String refFor(String taskId) {
        return REF_PREFIX + TaskIdSanitizer.branchName(taskId);
    }

    private static TaskLifecycleEvent eventFor(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> TaskLifecycleEvent.COMPLETED;
            case TaskOutcome.Paused ignored -> TaskLifecycleEvent.PAUSED;
            case TaskOutcome.Escalated ignored -> TaskLifecycleEvent.ESCALATED;
            case TaskOutcome.Aborted ignored -> TaskLifecycleEvent.ABORTED;
        };
    }
}
