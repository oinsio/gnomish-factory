package com.github.oinsio.gnomish.adapter.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.TaskRepository;
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
import java.nio.charset.StandardCharsets;
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

    /** {@code task.json} is a small factory-authored document; a 1&nbsp;MiB read cap is generous. */
    private static final long TASK_JSON_SIZE_CAP = 1L << 20;

    private static final String STATE_DIR = ".gnomish-task";
    private static final String TASK_JSON_PATH = STATE_DIR + "/task.json";
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
        TaskJsonDto dto = TaskJsonMapper.toDto(context, base.hex(), now, null, null, false);
        commit(taskId, ref, true, base, putTaskJson(taskId, dto), TaskLifecycleEvent.STARTED, now);
    }

    @Override
    public void appendDecision(String taskId, Decision decision) {
        String ref = refFor(taskId);
        ObjectId tip = requireTip(taskId, ref, TaskLifecycleEvent.RESUMED);
        TaskJsonContent current = readCurrent(taskId, tip, TaskLifecycleEvent.RESUMED);

        List<Decision> decisions = new ArrayList<>(current.context().decisions());
        decisions.add(decision);
        TaskContext updated = new TaskContext(
                current.context().taskId(),
                current.context().title(),
                current.context().body(),
                decisions);

        Instant now = Instant.now(clock);
        // Appending the resume decision resets outcome to null in the same commit (FR5/D9 contract).
        TaskJsonDto dto = TaskJsonMapper.toDto(
                updated, current.baseCommit(), current.createdAt(), null, current.lastEscalation(), false);
        commit(taskId, ref, false, tip, putTaskJson(taskId, dto), TaskLifecycleEvent.RESUMED, now);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        TaskLifecycleEvent event = eventFor(outcome);
        String ref = refFor(taskId);
        ObjectId tip = requireTip(taskId, ref, event);
        TaskJsonContent current = readCurrent(taskId, tip, event);

        EscalationReport lastEscalation =
                outcome instanceof TaskOutcome.Escalated escalated ? escalated.report() : current.lastEscalation();
        // A terminal PARK (Escalated/Paused) sets the durable "tracker-write pending" marker before
        // its git-unfenced tracker write, exactly as GitTaskRepository does (FR10/D10 of
        // add-claim-heartbeat); Completed reconciles via cleanup detection, Aborted's write is
        // best-effort.
        boolean pending = outcome instanceof TaskOutcome.Escalated || outcome instanceof TaskOutcome.Paused;
        Instant now = Instant.now(clock);
        TaskJsonDto dto = TaskJsonMapper.toDto(
                current.context(), current.baseCommit(), current.createdAt(), outcome, lastEscalation, pending);
        ObjectId outcomeCommit = commit(taskId, ref, false, tip, putTaskJson(taskId, dto), event, now);

        if (outcome instanceof TaskOutcome.Completed) {
            // The cleanup commit removes .gnomish-task/ from the tip; prior commits stay reachable as
            // the audit trail (FR15/M4). No live environment is required — the state commit was the
            // last in-box commit (D15/D19).
            CommitMetadata cleanupMeta = metadata(ServiceCommitMessages.cleanup(), now);
            build(
                    taskId,
                    new CommitRequest(
                            ref,
                            Optional.of(outcomeCommit),
                            outcomeCommit,
                            List.of(new TreeEdit.DeletePath(STATE_DIR)),
                            cleanupMeta),
                    TaskLifecycleEvent.COMPLETED);
        }
    }

    private ObjectId requireTip(String taskId, String ref, TaskLifecycleEvent event) {
        return gitObjects
                .resolveRef(ref)
                .orElseThrow(() -> new GitTaskRepositoryException(
                        taskId, event, "locating task branch", "no branch \"" + ref + "\" exists"));
    }

    private TaskJsonContent readCurrent(String taskId, ObjectId tip, TaskLifecycleEvent event) {
        byte[] bytes;
        try {
            bytes = gitObjects.readBlob(tip, TASK_JSON_PATH, TASK_JSON_SIZE_CAP);
        } catch (RuntimeException e) {
            throw new GitTaskRepositoryException(taskId, event, "reading task.json", e);
        }
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8)));
    }

    private List<TreeEdit> putTaskJson(String taskId, TaskJsonDto dto) {
        try {
            byte[] bytes = TaskStateJson.mapper().writeValueAsString(dto).getBytes(StandardCharsets.UTF_8);
            return List.of(new TreeEdit.PutFile(TASK_JSON_PATH, bytes));
        } catch (JsonProcessingException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.STARTED, "serializing task.json", e);
        }
    }

    private ObjectId commit(
            String taskId,
            String ref,
            boolean newBranch,
            ObjectId parent,
            List<TreeEdit> edits,
            TaskLifecycleEvent event,
            Instant now) {
        Optional<ObjectId> expectedTip = newBranch ? Optional.empty() : Optional.of(parent);
        return build(taskId, new CommitRequest(ref, expectedTip, parent, edits, metadata(event, now)), event);
    }

    private ObjectId build(String taskId, CommitRequest request, TaskLifecycleEvent event) {
        try {
            return gitObjects.commit(request);
        } catch (StaleTipException e) {
            throw new GitTaskRepositoryException(taskId, event, "advancing task branch (tip moved concurrently)", e);
        } catch (RuntimeException e) {
            throw new GitTaskRepositoryException(taskId, event, "building lifecycle commit", e);
        }
    }

    private CommitMetadata metadata(TaskLifecycleEvent event, Instant now) {
        return metadata(ServiceCommitMessages.taskEvent(event), now);
    }

    private CommitMetadata metadata(String message, Instant now) {
        return new CommitMetadata(identity, now, identity, now, message);
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
