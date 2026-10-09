package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code task.json} an <em>outcome-clearing</em> write commits: the decision commit, the
 * approval commit and the resumed commit (FR5/D9 of add-git-workflow; FR3, FR7, FR8 of
 * make-checkpoint-gate-durable). Each
 * begins a new visit, so the recorded {@code outcome} is cleared and the tracker-write-pending
 * marker set to false, while everything else the tip carries goes forward verbatim — the {@code
 * baseCommit}, {@code createdAt}, pin, {@code lastEscalation} (display data, so the last question
 * stays visible after the resume) and the denial cursor (FR5 of
 * fix-denial-attribution-durability: a lifecycle rewrite reads no denial source, so it must not
 * erase the position the tip carries).
 *
 * <p>One owner for both media: {@link GitTaskRepository} and {@link GitObjectsTaskRepository}
 * build the document here, so the fields an outcome-clearing write lands cannot drift between the
 * worktree commit and the bare-object commit.
 *
 * <p>Implements FR5 of add-git-workflow; FR3, FR8 of make-checkpoint-gate-durable.
 */
final class OutcomeClearingTaskJson {

    private OutcomeClearingTaskJson() {}

    /**
     * The tip's document with its outcome consumed and nothing added — the approval's and the
     * resumed write's {@code task.json}.
     *
     * @param current the tip's {@code task.json} as its raw wire DTO
     * @return the document to commit
     */
    static TaskJsonDto of(TaskJsonDto current) {
        TaskRecord record = TaskJsonMapper.fromDto(current);
        return rewrite(current, record, record.context());
    }

    /**
     * The tip's document with its outcome consumed and {@code decision} appended to the decisions
     * — the decision commit's {@code task.json}.
     *
     * @param current the tip's {@code task.json} as its raw wire DTO
     * @param decision the human decision the resume appends
     * @return the document to commit
     */
    static TaskJsonDto withDecision(TaskJsonDto current, Decision decision) {
        TaskRecord record = TaskJsonMapper.fromDto(current);
        TaskContext context = record.context();
        List<Decision> decisions = new ArrayList<>(context.decisions());
        decisions.add(decision);
        return rewrite(current, record, new TaskContext(context.taskId(), context.title(), context.body(), decisions));
    }

    private static TaskJsonDto rewrite(TaskJsonDto current, TaskRecord record, TaskContext context) {
        return TaskJsonMapper.toDto(
                        context,
                        record.baseCommit(),
                        record.createdAt(),
                        null,
                        record.lastEscalation(),
                        false,
                        record.pin())
                .withEgressCursor(current.egressCursor());
    }
}
