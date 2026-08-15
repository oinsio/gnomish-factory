package com.github.oinsio.gnomish.domain.pipeline;

/**
 * An artifact a stage consumes, modeled as one of two sealed variants (design
 * D5) so validators and the future engine can switch exhaustively: an
 * {@link Internal} input produced by an earlier stage, or a {@link Source}
 * input that has no producing stage. Both are inert data — resolving an
 * internal reference to a strictly-earlier stage's output is the DAG
 * validator's concern (design D4, task 4.3), reported as a located
 * {@link ConfigError}, never a constructor exception.
 *
 * <p>Implements FR4 of load-pipeline-config.
 */
public sealed interface ArtifactInput {

    /**
     * An input produced by an earlier stage in the pipeline, referenced by the
     * stable {@code id} of that stage's {@link ArtifactOutput}. The reference is
     * carried unresolved: dangling and forward references are the DAG
     * validator's territory (task 4.3).
     *
     * <p>Implements FR4 of load-pipeline-config.
     *
     * @param producerOutputId the {@link ArtifactOutput#id()} of an output
     *     declared by a stage that appears earlier in the pipeline order
     *     (earlier-than resolution validated by task 4.3, not here)
     */
    record Internal(String producerOutputId) implements ArtifactInput {}

    /**
     * An input with no producing stage: it arrives with the task's working copy
     * (e.g. the project source tree) rather than from an earlier stage, so the
     * DAG validator requires no producer for it (FR4). A field-less marker —
     * the delta spec demands only the declaration that no producer exists.
     *
     * <p>Implements FR4 of load-pipeline-config.
     */
    record Source() implements ArtifactInput {}
}
