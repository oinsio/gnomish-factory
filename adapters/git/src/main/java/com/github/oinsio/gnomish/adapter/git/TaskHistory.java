package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.MalformedStateFileException;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import com.github.oinsio.gnomish.usage.wire.UsageSnapshotWire;
import java.nio.file.Path;

/**
 * A task's own history on its branch (design D13, FR19 of define-executor-contract): the revision
 * range {@code <baseCommit>..<registered commit>}, the task id its records must name, and the
 * usage snapshot the tip records — the {@code totals} a {@code gnomish usage} re-fold of the walked
 * rounds is compared with (design D15, FR18).
 *
 * <p>All three are read at the register's envelope revision ({@link
 * FactoryRegisterRead#envelopeRevision}): the range and the task id from {@code task.json} through
 * the task-json reader, the snapshot from {@code state.json} through the state-file reader. The
 * snapshot is the round writers' figure; the walk's rows are the records of every round — two
 * sources, so the comparison is a check, never a value compared with its own re-read. {@code baseCommit} is the
 * identity the factory recorded at creation — never a {@code merge-base}, which recomputes and
 * moves after a squash merge — so a base whose tree already carried another task's envelope
 * contributes no commit to the range. It becomes a revision argument, so a value that is not a
 * full object id is refused rather than handed to git.
 *
 * <p>A class, not a record: its factory carries the decisions, and PIT cannot redefine a record's
 * methods reliably (hcoles/pitest#1285).
 *
 * <p>Implements FR19 of define-executor-contract.
 */
final class TaskHistory {

    private final String taskId;
    private final String revisions;
    private final UsageSnapshot recordedTotals;

    private TaskHistory(String taskId, String revisions, UsageSnapshot recordedTotals) {
        this.taskId = taskId;
        this.revisions = revisions;
        this.recordedTotals = recordedTotals;
    }

    /** The task id {@code task.json} records. */
    String taskId() {
        return taskId;
    }

    /** The range {@code <baseCommit>..<registered>}, for {@code git log}. */
    String revisions() {
        return revisions;
    }

    /** The {@code totals} the tip's {@code state.json} records. */
    UsageSnapshot recordedTotals() {
        return recordedTotals;
    }

    /**
     * Reads the history of the task whose register names {@code registered}.
     *
     * @throws BranchStateFileMissingException when the envelope revision carries no {@code
     *     task.json} or no {@code state.json}
     * @throws MalformedStateFileException when its {@code baseCommit} is not a full object id, or
     *     either file cannot be read
     */
    static TaskHistory of(GitRunner runner, Path cloneDir, RegisteredCommit registered) {
        RegisteredCommit envelope = new FactoryRegisterRead(runner).envelopeRevision(cloneDir, registered);
        GitShowTip reader = new GitShowTip(runner, cloneDir, envelope);
        TaskJsonDto task = TaskJsonMapper.readDto(read(reader, envelope, EnvelopePaths.TASK_JSON_PATH));
        if (!VerifiedTip.isObjectId(task.baseCommit())) {
            throw new MalformedStateFileException(EnvelopePaths.TASK_FILE, "baseCommit is not a full object id");
        }
        UsageSnapshot totals =
                UsageSnapshotWire.fromDto(StateJsonMapper.readDto(read(reader, envelope, EnvelopePaths.STATE_JSON_PATH))
                        .totals());
        return new TaskHistory(task.taskId(), task.baseCommit() + ".." + registered.id(), totals);
    }

    private static UntrustedText read(GitShowTip reader, RegisteredCommit envelope, String path) {
        return reader.readAtRegister(path)
                .orElseThrow(() -> new BranchStateFileMissingException(
                        envelope.id(), path, UntrustedText.factory("the envelope revision carries no " + path)));
    }
}
