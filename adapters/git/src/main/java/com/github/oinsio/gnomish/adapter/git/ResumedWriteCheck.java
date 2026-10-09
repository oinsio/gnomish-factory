package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The resumed write's refusal rule and its log line, owned once for both media (FR7, NFR-O1,
 * NFR-R2 of make-checkpoint-gate-durable, design D4): {@link GitTaskRepository} and {@link
 * GitObjectsTaskRepository} read their tip's {@code task.json} each by its own mechanism and hand
 * the record here, so the two cannot refuse on different tip conditions.
 *
 * <p>The rule is decided on what the tip alone shows: a resumed write consumes a recorded outcome,
 * so a tip whose {@code outcome} is already null has nothing to consume and the write refuses. A
 * resumed write repeated over a tip it already moved therefore refuses rather than granting the
 * attempt reset twice (NFR-R2). The refusal is not logged here: it is thrown to the continuation
 * that asked, which decides what it means (one failure, one log).
 *
 * <p>Implements FR7, NFR-O1, NFR-R2 of make-checkpoint-gate-durable.
 */
final class ResumedWriteCheck {

    private static final Logger log = LoggerFactory.getLogger(ResumedWriteCheck.class);

    private ResumedWriteCheck() {}

    /**
     * Refuses, before any commit step, a resumed write over a tip that records no outcome.
     *
     * @param taskId the task being resumed; for the refusal
     * @param tip the record the branch tip's {@code task.json} carries
     * @return the outcome the write consumes, for its log line
     * @throws ResumedWriteRefusedException when the tip's {@code outcome} is null
     */
    static RecordedOutcome requireRecordedOutcome(String taskId, TaskRecord tip) {
        RecordedOutcome outcome = tip.outcome();
        if (outcome == null) {
            throw new ResumedWriteRefusedException(taskId);
        }
        return outcome;
    }

    /**
     * The resumed write's INFO line, after its commit landed (NFR-O1): the task, the outcome it
     * consumed and the position the task resumes at.
     *
     * @param taskId the resumed task
     * @param consumed the outcome the commit cleared
     * @param reset the state the commit wrote
     */
    static void resumed(String taskId, RecordedOutcome consumed, TaskState reset) {
        log.info(
                "resumed write for task {}: the recorded {} outcome is consumed, position now {}",
                taskId,
                consumed.getClass().getSimpleName(),
                reset.position());
    }
}
