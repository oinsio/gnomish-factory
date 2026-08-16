package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * The terminal outcome a task repository recorded for a task, as the resume path reads it back:
 * {@code null} while a visit is in progress, reset at the start of each resumed visit (FR5 of
 * add-git-workflow), otherwise one of the four {@link TaskOutcome} kinds mirrored 1:1.
 *
 * <p>Deliberately NOT the domain {@link TaskOutcome}: a recorded outcome alone lacks the data a
 * domain {@code TaskOutcome} requires — {@code finalState} is held separately by the repository's
 * state record, and {@code Aborted.failedAt}'s structured {@code AttemptKey} survives only as an
 * opaque label. Reconstructing a full domain outcome belongs to whichever caller joins the two.
 *
 * <p>Equally deliberately NOT the git adapter's {@code task.json} DTO, which is what the resume
 * path used to pattern-match on: the wire format is an adapter contract, and a port-level outcome
 * typed in it would bind {@code application} to the git adapter's serialization shape, discriminator
 * strings and all (FR12b, design D12 of split-into-modules). The adapter maps its DTO onto this
 * type on the way out; no discriminator component appears here, because nothing above the adapter
 * has any use for one.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR12b of split-into-modules.
 */
public sealed interface RecordedOutcome {

    /** Every stage passed; the pipeline reached its end. */
    record Completed() implements RecordedOutcome {}

    /**
     * A manual checkpoint paused the run.
     *
     * @param passedStage the stage that passed and triggered the pause
     */
    record Paused(String passedStage) implements RecordedOutcome {}

    /**
     * A human is needed.
     *
     * @param report why the run escalated
     */
    record Escalated(EscalationReport report) implements RecordedOutcome {}

    /**
     * A persistence failure broke the durability guarantee.
     *
     * @param failedAt human-readable identity of the round whose persist failed
     * @param cause the failure detail, stack trace preserved
     */
    record Aborted(String failedAt, String cause) implements RecordedOutcome {}
}
