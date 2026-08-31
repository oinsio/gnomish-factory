package com.github.oinsio.gnomish.domain.branch;

/**
 * What a reader found when it opened one {@code .gnomish-task/} envelope at a branch tip. The
 * classifier consumes this instead of raw JSON: reading and version-gating a wire format belongs to
 * the adapter that owns it, deciding what the result means belongs here (design D3).
 *
 * <p>Total by construction — a read that fails is a status, never an exception — which is half of
 * "the classifier never throws on content" (NFR-R2).
 *
 * <p>Implements FR1, FR15, NFR-R2 of harden-task-branch-contract.
 */
public sealed interface EnvelopeStatus {

    /** The envelope is not present at the tip. */
    record Absent() implements EnvelopeStatus {}

    /** The envelope parsed at a supported version; its content facts are on {@link BranchTipFacts}. */
    record Parsed() implements EnvelopeStatus {}

    /**
     * The envelope declares a version this factory does not support.
     *
     * @param observedVersion the version found on the wire, or {@code -1} when the field was absent
     * @param supportedVersion the version this factory supports
     */
    record UnsupportedVersion(int observedVersion, int supportedVersion) implements EnvelopeStatus {}

    /**
     * The envelope is present at a supported version but its content could not be read.
     *
     * @param reason what failed, naming the observed versus expected content
     */
    record Unreadable(String reason) implements EnvelopeStatus {}
}
