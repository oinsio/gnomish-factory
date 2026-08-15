package com.github.oinsio.gnomish.adapter.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.gitobjects.CommitIdentity;
import com.github.oinsio.gnomish.gitobjects.CommitMetadata;
import com.github.oinsio.gnomish.gitobjects.CommitRequest;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.gitobjects.StaleTipException;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The bare-object commit-building plumbing behind {@link GitObjectsTaskRepository} (design D19):
 * reading the current {@code task.json} off a branch tip, serializing an updated one into a tree
 * edit, and building the lifecycle commit with git's atomic compare-and-swap. Extracted from
 * {@link GitObjectsTaskRepository} for file size; the behavior is unchanged.
 */
record TaskLifecycleCommitWriter(GitObjects gitObjects, CommitIdentity identity, Instant now) {

    /** {@code task.json} is a small factory-authored document; a 1&nbsp;MiB read cap is generous. */
    private static final long TASK_JSON_SIZE_CAP = 1L << 20;

    private static final String STATE_DIR = ".gnomish-task";
    private static final String TASK_JSON_PATH = STATE_DIR + "/task.json";

    ObjectId requireTip(String taskId, String ref, TaskLifecycleEvent event) {
        return gitObjects
                .resolveRef(ref)
                .orElseThrow(() -> new GitTaskRepositoryException(
                        taskId, event, "locating task branch", "no branch \"" + ref + "\" exists"));
    }

    TaskRecord readCurrent(String taskId, ObjectId tip, TaskLifecycleEvent event) {
        byte[] bytes;
        try {
            bytes = gitObjects.readBlob(tip, TASK_JSON_PATH, TASK_JSON_SIZE_CAP);
        } catch (RuntimeException e) {
            throw new GitTaskRepositoryException(taskId, event, "reading task.json", e);
        }
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8)));
    }

    List<TreeEdit> putTaskJson(String taskId, TaskJsonDto dto) {
        try {
            byte[] bytes = TaskStateJson.mapper().writeValueAsString(dto).getBytes(StandardCharsets.UTF_8);
            return List.of(new TreeEdit.PutFile(TASK_JSON_PATH, bytes));
        } catch (JsonProcessingException e) {
            throw new GitTaskRepositoryException(taskId, TaskLifecycleEvent.STARTED, "serializing task.json", e);
        }
    }

    ObjectId commit(
            String taskId,
            String ref,
            boolean newBranch,
            ObjectId parent,
            List<TreeEdit> edits,
            TaskLifecycleEvent event) {
        Optional<ObjectId> expectedTip = newBranch ? Optional.empty() : Optional.of(parent);
        return build(taskId, new CommitRequest(ref, expectedTip, parent, edits, metadata(event)), event);
    }

    ObjectId build(String taskId, CommitRequest request, TaskLifecycleEvent event) {
        try {
            return gitObjects.commit(request);
        } catch (StaleTipException e) {
            throw new GitTaskRepositoryException(taskId, event, "advancing task branch (tip moved concurrently)", e);
        } catch (RuntimeException e) {
            throw new GitTaskRepositoryException(taskId, event, "building lifecycle commit", e);
        }
    }

    CommitMetadata metadata(TaskLifecycleEvent event) {
        return metadata(ServiceCommitMessages.taskEvent(event));
    }

    CommitMetadata metadata(String message) {
        return new CommitMetadata(identity, now, identity, now, message);
    }

    /** The state-directory tree-edit deletion path for the {@code Completed} cleanup commit. */
    static String stateDir() {
        return STATE_DIR;
    }
}
