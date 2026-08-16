package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.gitobjects.CommitIdentity;
import com.github.oinsio.gnomish.gitobjects.CommitMetadata;
import com.github.oinsio.gnomish.gitobjects.CommitRequest;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.StaleTipException;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
public final class GitObjectsTaskRepository implements TaskRepository {

    private static final String REF_PREFIX = "refs/heads/";

    /** The factory identity that authors lifecycle commits when none is supplied (design D19). */
    private static final CommitIdentity DEFAULT_IDENTITY =
            new CommitIdentity("gnomish-factory", "gnomish-factory@localhost");

    private final GitObjects gitObjects;
    private final CommitIdentity identity;
    private final Clock clock;

    /**
     * @param gitObjects the bare-object facade opened against the factory clone (git dir + a
     *     factory-private temp dir for indexes)
     */
    public GitObjectsTaskRepository(GitObjects gitObjects) {
        this(gitObjects, DEFAULT_IDENTITY, Clock.systemUTC());
    }

    /**
     * @param gitObjects the bare-object facade opened against the factory clone
     * @param identity the name/email git records as author and committer of lifecycle commits
     * @param clock the source of commit timestamps and {@code createdAt} — injectable so specs pin
     *     deterministic commit ids (design D19)
     */
    public GitObjectsTaskRepository(GitObjects gitObjects, CommitIdentity identity, Clock clock) {
        this.gitObjects = gitObjects;
        this.identity = identity;
        this.clock = clock;
    }

    @Override
    public void createTask(TaskContext context, String baseRef) {
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
        var writer = new TaskLifecycleCommitWriter(gitObjects, identity, now);
        TaskJsonDto dto = TaskJsonMapper.toDto(context, base.hex(), now, null, null, false);
        writer.commit(taskId, ref, true, base, writer.putTaskJson(taskId, dto), TaskLifecycleEvent.STARTED);
    }

    @Override
    public void appendDecision(String taskId, Decision decision) {
        String ref = refFor(taskId);
        var writer = new TaskLifecycleCommitWriter(gitObjects, identity, Instant.now(clock));
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
        writer.commit(taskId, ref, false, tip, writer.putTaskJson(taskId, dto), TaskLifecycleEvent.RESUMED);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        TaskLifecycleEvent event = eventFor(outcome);
        String ref = refFor(taskId);
        var writer = new TaskLifecycleCommitWriter(gitObjects, identity, Instant.now(clock));
        ObjectId tip = writer.requireTip(taskId, ref, event);
        TaskRecord current = writer.readCurrent(taskId, tip, event);

        EscalationReport lastEscalation =
                outcome instanceof TaskOutcome.Escalated escalated ? escalated.report() : current.lastEscalation();
        // A terminal PARK (Escalated/Paused) sets the durable "tracker-write pending" marker before
        // its git-unfenced tracker write, exactly as GitTaskRepository does (FR10/D10 of
        // add-claim-heartbeat); Completed reconciles via cleanup detection, Aborted's write is
        // best-effort.
        boolean pending = outcome instanceof TaskOutcome.Escalated || outcome instanceof TaskOutcome.Paused;
        TaskJsonDto dto = TaskJsonMapper.toDto(
                current.context(), current.baseCommit(), current.createdAt(), outcome, lastEscalation, pending);
        ObjectId outcomeCommit = writer.commit(taskId, ref, false, tip, writer.putTaskJson(taskId, dto), event);

        if (outcome instanceof TaskOutcome.Completed) {
            // The cleanup commit removes .gnomish-task/ from the tip; prior commits stay reachable as
            // the audit trail (FR15/M4). No live environment is required — the state commit was the
            // last in-box commit (D15/D19).
            CommitMetadata cleanupMeta = writer.metadata(ServiceCommitMessages.cleanup());
            writer.build(
                    taskId,
                    new CommitRequest(
                            ref,
                            Optional.of(outcomeCommit),
                            outcomeCommit,
                            List.of(new TreeEdit.DeletePath(TaskLifecycleCommitWriter.stateDir())),
                            cleanupMeta),
                    TaskLifecycleEvent.COMPLETED);
        }
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
