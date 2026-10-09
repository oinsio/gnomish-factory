package com.github.oinsio.gnomish.app.port;

import java.io.Serial;

/**
 * Thrown by {@link TaskRepository#resumeFrom} when the branch tip's {@code task.json} records no
 * outcome — there is nothing for the resumed write to consume. Nothing was written: the refusal is
 * decided on what the tip alone shows, before any commit step (design D4, NFR-R2 of
 * make-checkpoint-gate-durable), so a resumed write repeated on a tip it already moved refuses and
 * changes nothing, and the attempt reset it carries is granted once.
 *
 * <p>Implements FR7, NFR-R2 of make-checkpoint-gate-durable.
 */
public final class ResumedWriteRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param taskId the task whose resumed write was refused; never blank */
    public ResumedWriteRefusedException(String taskId) {
        super("resumed write refused for task \"" + taskId
                + "\": the branch tip records no outcome to consume (outcome is already null)");
    }
}
