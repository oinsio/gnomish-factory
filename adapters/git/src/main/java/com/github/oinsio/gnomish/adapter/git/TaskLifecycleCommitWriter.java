package com.github.oinsio.gnomish.adapter.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.CommitIdentity;
import com.github.oinsio.gnomish.gitobjects.CommitMetadata;
import com.github.oinsio.gnomish.gitobjects.CommitRequest;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.StaleTipException;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bare-object commit-building plumbing behind {@link GitObjectsTaskRepository} (design D19):
 * reading the current {@code task.json} off a branch tip, serializing an updated one into a tree
 * edit, and building the lifecycle commit with git's atomic compare-and-swap. Extracted from
 * {@link GitObjectsTaskRepository} for file size; the behavior is unchanged.
 *
 * <p>Every message it builds goes out through {@link #metadata(String, String)}, which stamps the
 * tenure's claim epoch as a trailer (FR13 of harden-task-branch-contract) — the container-mode twin
 * of what {@link GitTaskRepository} does on the host, and the reason there is one message-building
 * method here rather than a stamp at each call site.
 */
record TaskLifecycleCommitWriter(GitObjects gitObjects, CommitIdentity identity, Instant now, ClaimEpochSource epochs) {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleCommitWriter.class);

    /** {@code task.json} is a small factory-authored document; a 1&nbsp;MiB read cap is generous. */
    private static final long TASK_JSON_SIZE_CAP = 1L << 20;

    private static final String STATE_DIR = ".gnomish-task";
    private static final String TASK_JSON_PATH = GnomishTaskPaths.TASK_JSON_PATH;
    private static final String STATE_JSON_PATH = GnomishTaskPaths.STATE_JSON_PATH;

    ObjectId requireTip(String taskId, String ref, TaskLifecycleEvent event) {
        return gitObjects
                .resolveRef(ref)
                .orElseThrow(() -> new GitTaskRepositoryException(
                        taskId, event, "locating task branch", "no branch \"" + ref + "\" exists"));
    }

    TaskRecord readCurrent(String taskId, ObjectId tip, TaskLifecycleEvent event) {
        return TaskJsonMapper.fromDto(readCurrentDto(taskId, tip, event));
    }

    /**
     * The tip's {@code task.json} as its raw wire DTO. The marker-clearing rewrite reads it this way
     * rather than through the domain record: the recorded outcome, decisions, and escalation are
     * preserved verbatim, which rebuilding from a domain outcome could not do (the host twin,
     * {@link TerminalWriteMarker}, reads it the same way and for the same reason).
     */
    TaskJsonDto readCurrentDto(String taskId, ObjectId tip, TaskLifecycleEvent event) {
        byte[] bytes;
        try {
            bytes = gitObjects.readBlob(tip, TASK_JSON_PATH, TASK_JSON_SIZE_CAP);
        } catch (RuntimeException e) {
            throw new GitTaskRepositoryException(taskId, event, "reading task.json", e);
        }
        return TaskJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8));
    }

    List<TreeEdit> putTaskJson(String taskId, TaskJsonDto dto) {
        try {
            byte[] bytes = TaskStateJson.mapper().writeValueAsString(dto).getBytes(StandardCharsets.UTF_8);
            return List.of(new TreeEdit.PutFile(TASK_JSON_PATH, bytes));
        } catch (JsonProcessingException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.STARTED, "serializing task.json", e);
        }
    }

    /**
     * The two tree edits of a lifecycle commit that carries both envelopes: {@code task.json} and
     * the {@code state.json} beside it (FR3, FR4 of harden-task-branch-contract) — the synthesized
     * initial state at STARTED, the attempt-counter reset at RESUMED. Mutually-implied fields land
     * in one commit, so no kill window freezes a branch carrying one without the other.
     */
    List<TreeEdit> putTaskAndState(String taskId, TaskJsonDto dto, TaskState state) {
        try {
            byte[] stateJson = TaskStateJson.mapper()
                    .writeValueAsString(StateJsonMapper.toDto(state))
                    .getBytes(StandardCharsets.UTF_8);
            List<TreeEdit> edits = new ArrayList<>(putTaskJson(taskId, dto));
            edits.add(new TreeEdit.PutFile(STATE_JSON_PATH, stateJson));
            return List.copyOf(edits);
        } catch (JsonProcessingException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.STARTED, "serializing state.json", e);
        }
    }

    // Void, not the ObjectId git hands back: no caller has ever used the new commit id — the ref
    // is already advanced by the compare-and-swap inside, so "which commit" is a question every
    // reader answers by resolving the ref. A returned id nobody reads is a value no test can
    // assert, which is exactly the shape that leaves an unkillable mutant behind.
    void commit(
            String taskId,
            String ref,
            boolean newBranch,
            ObjectId parent,
            List<TreeEdit> edits,
            TaskLifecycleEvent event) {
        Optional<ObjectId> expectedTip = newBranch ? Optional.empty() : Optional.of(parent);
        build(taskId, new CommitRequest(ref, expectedTip, parent, edits, metadata(taskId, event)), event);
    }

    /**
     * The bare-objects medium's task-lifecycle commit choke point — every lifecycle transition
     * recorded without a worktree passes through here, so the FR2 anchor of
     * harden-logging-observability sits here rather than at each caller.
     *
     * <p>Kept in sync with {@link GitTaskRepository}: both media log one INFO line per lifecycle
     * transition, after the commit succeeds, naming the task and the event — an anchor states that
     * the transition is on the branch, and a failed commit has not put it there.
     */
    void build(String taskId, CommitRequest request, TaskLifecycleEvent event) {
        try {
            gitObjects.commit(request);
        } catch (StaleTipException e) {
            throw new GitTaskRepositoryException(taskId, event, "advancing task branch (tip moved concurrently)", e);
        } catch (RuntimeException e) {
            throw new GitTaskRepositoryException(taskId, event, "building lifecycle commit", e);
        }
        log.info("task lifecycle commit written for task {}: event={}", taskId, event);
    }

    CommitMetadata metadata(String taskId, TaskLifecycleEvent event) {
        return metadata(taskId, ServiceCommitMessages.taskEvent(event));
    }

    CommitMetadata metadata(String taskId, String message) {
        return new CommitMetadata(
                identity,
                now,
                identity,
                now,
                ClaimEpochTrailer.stamp(message, epochs.epochFor(taskId).orElse(null)));
    }

    /** The state-directory tree-edit deletion path for the {@code Completed} cleanup commit. */
    static String stateDir() {
        return STATE_DIR;
    }

    /** The task envelope's path at a tip — the presence test for "this branch still has an envelope". */
    static String taskJsonPath() {
        return TASK_JSON_PATH;
    }
}
