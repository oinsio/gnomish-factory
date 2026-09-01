package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.state.StateEgressCursorDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.MissingObjectException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything a container run reads back from its task branch as bare git objects, with no working
 * copy and no live box (FR6, FR17 of add-sandbox-core): the tip's {@code state.json} and {@code
 * task.json}, and the denial cursor recorded alongside them.
 *
 * <p>Split out of {@link ContainerRunTermination}, whose remaining half writes the run's terminal
 * boundary — sweep, complete, park, abort, keep. The two halves face opposite directions across the
 * same branch, and this one owns what that makes it responsible for: where the tip comes from, the
 * read cap every factory-authored file is read under, and — the decision that gives the split its
 * point — which read failures are legal branch shapes and which are faults. A caller of {@link
 * #readStateOrInitial} needs that policy to be one class's answer, not a {@code catch} clause
 * buried among lifecycle writes.
 */
final class ContainerTipReader {

    private static final Logger log = LoggerFactory.getLogger(ContainerTipReader.class);

    /** Factory-authored state files are small; a 1&nbsp;MiB read cap is generous (NFR-S3). */
    private static final long FILE_READ_CAP = 1L << 20;

    private ContainerTipReader() {}

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
     *
     * <p>Only an ABSENT state file degrades ({@link MissingObjectException}); one that exists but
     * will not read back — malformed JSON, a blob past the read cap, a branch that vanished
     * mid-run — propagates. "No rounds recorded yet" is a legal branch shape, while "rounds
     * recorded but unreadable" is a fault, and absorbing the fault would silently rewind a task
     * with recorded progress to its first stage and replay work the branch already holds. The host
     * twin {@code HostResumeMechanics#readFinalState} narrows the same way, on {@code
     * NoSuchFileException}, and likewise logs nothing on its legal arm.
     */
    static TaskState readStateOrInitial(ContainerRunSupport support, String firstStage) {
        try {
            return readFinalState(support);
        } catch (MissingObjectException absent) {
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
            log.debug("no recorded denial cursor to restore", e);
            return;
        }
        if (cursor != null) {
            support.environments.restoreDenialCursor(new DenialCursor(cursor.source(), cursor.position()));
        }
    }

    private static ObjectId tip(ContainerRunSupport support) {
        return support.gitObjects
                .resolveRef("refs/heads/" + support.branch)
                .orElseThrow(() -> new IllegalStateException("task branch \"" + support.branch + "\" disappeared"));
    }
}
