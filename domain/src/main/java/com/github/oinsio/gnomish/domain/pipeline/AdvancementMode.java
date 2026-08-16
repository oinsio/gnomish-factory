package com.github.oinsio.gnomish.domain.pipeline;

/**
 * The Advancement section of the stage contract (§8): how the pipeline moves
 * on once a stage's verification passes. {@link #AUTO} proceeds to the next
 * stage immediately; {@link #MANUAL} is a debug checkpoint — artifacts and
 * state are committed and the task waits in the tracker until a human returns
 * it to work. Modeled as inert data (NG1): the future stage engine gives the
 * modes their runtime semantics, and an unknown wire value is a located
 * structural error in the adapter (FR5).
 *
 * <p>Implements FR2 of load-pipeline-config.
 */
public enum AdvancementMode {
    /** Proceed to the next stage as soon as verification passes (default). */
    AUTO,
    /** Debug checkpoint: wait in the tracker for a human to resume. */
    MANUAL
}
