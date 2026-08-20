package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.state.StateEgressCursorDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The terminal-boundary and branch-tip-reading operations of {@link ContainerRunSupport} (FR6,
 * FR11, FR17, FR21, FR22, NFR-R2 of add-sandbox-core): the sweep, completion, abort, and keep
 * paths, plus reading the last durably committed state back from bare git objects. Extracted from
 * {@link ContainerRunSupport} for file size; the behavior is unchanged.
 */
final class ContainerRunTermination {

    private static final Logger log = LoggerFactory.getLogger(ContainerRunTermination.class);

    /** Factory-authored state files are small; a 1&nbsp;MiB read cap is generous (NFR-S3). */
    private static final long FILE_READ_CAP = 1L << 20;

    private ContainerRunTermination() {}

    /**
     * Runs the startup orphan sweep (FR11, NFR-R2): objects a dead instance left labelled but no
     * live task owns are removed, this task's own environments preserved. Delegates to the
     * environments seam; a missing Docker runtime is a logged no-op, never a failure.
     */
    static void sweepOrphans(ContainerRunSupport support) {
        support.environments.sweepOrphans();
    }

    /**
     * Completed terminal boundary (D19 ordering): dispose the environment first — the last
     * in-box commit was the state commit — then record the outcome and cleanup commits
     * factory-side, then push best-effort.
     */
    static void completeAndDispose(ContainerRunSupport support, TaskState finalState) {
        support.judgeEnvironments.disposeCurrent();
        support.lease.dispose();
        support.taskRepository.recordOutcome(support.taskId, new TaskOutcome.Completed(finalState));
        support.push.pushBestEffort(support.cloneDir, support.branch);
    }

    /**
     * Aborted terminal boundary (D19): the outcome commits on the last harvested tip and is
     * pushed best-effort; the violating box is kept as evidence — the caller's keep path stops
     * it (see {@code ContainerRunSupport.keepStopped}), volume and network retained.
     */
    static void recordAborted(ContainerRunSupport support, TaskOutcome.Aborted outcome) {
        support.taskRepository.recordOutcome(support.taskId, outcome);
        support.push.pushBestEffort(support.cloneDir, support.branch);
    }

    /**
     * Keep semantics for a run that ended without disposing (aborted, EOF-interrupted dialog):
     * the container is stopped so no gnome process keeps executing, volume and network remain
     * for salvage and resume; fresh judge boxes are disposed — they hold nothing durable.
     */
    static void keepStopped(ContainerRunSupport support) {
        support.judgeEnvironments.disposeCurrent();
        support.environments.stopKeeping();
    }

    /** Reads the last durably committed {@code state.json} from the branch tip as bare objects (FR17). */
    static TaskState readFinalState(ContainerRunSupport support) {
        return StateJsonMapper.fromDto(readStateDto(support));
    }

    /** The branch tip's {@code state.json} as its wire DTO — the domain state plus what it omits. */
    private static StateJsonDto readStateDto(ContainerRunSupport support) {
        byte[] bytes = support.gitObjects.readBlob(tip(support), ".gnomish-task/state.json", FILE_READ_CAP);
        return StateJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * The recorded state at the branch tip, or the initial state at {@code firstStage} when no
     * round ever persisted one — a task killed during its very first round has only the creation
     * commit's {@code task.json} on the branch (FR6).
     */
    static TaskState readStateOrInitial(ContainerRunSupport support, String firstStage) {
        try {
            return readFinalState(support);
        } catch (RuntimeException e) {
            return TaskState.atStageStart(firstStage);
        }
    }

    /** Reads the branch tip's {@code task.json} as bare objects — context, outcome, escalation (FR17). */
    static TaskRecord readTaskJson(ContainerRunSupport support) {
        byte[] bytes = support.gitObjects.readBlob(tip(support), ".gnomish-task/task.json", FILE_READ_CAP);
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(new String(bytes, StandardCharsets.UTF_8)));
    }

    /**
     * Hands the run's environments the denial cursor the branch tip's {@code state.json} recorded
     * (FR5 of fix-denial-report-attachment). Best-effort by design: a branch with no state file,
     * no cursor in it, or an unreadable tip leaves the environments reading their denial source
     * from its start — the behavior of every run before the cursor existed, and correct whenever
     * the source is new. A cursor naming a different source is dropped by the environment itself.
     */
    static void restoreDenialCursor(ContainerRunSupport support) {
        StateEgressCursorDto cursor;
        try {
            cursor = readStateDto(support).egressCursor();
        } catch (RuntimeException e) {
            log.debug("no recorded denial cursor to restore: {}", e.toString());
            return;
        }
        if (cursor != null) {
            support.environments.restoreDenialCursor(new DenialCursor(cursor.source(), cursor.position()));
        }
    }

    /** Disposes a kept environment left by a previous instance ({@code --discard-work}, FR6). */
    static void disposeExistingEnvironment(ContainerRunSupport support) {
        support.environments.disposeExisting();
    }

    private static ObjectId tip(ContainerRunSupport support) {
        return support.gitObjects
                .resolveRef("refs/heads/" + support.branch)
                .orElseThrow(() -> new IllegalStateException("task branch \"" + support.branch + "\" disappeared"));
    }
}
