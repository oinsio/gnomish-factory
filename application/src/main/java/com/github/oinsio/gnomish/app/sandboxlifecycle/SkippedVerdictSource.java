package com.github.oinsio.gnomish.app.sandboxlifecycle;

/**
 * Where a {@link SweepVerdictCategory#SKIPPED_NO_VERDICT} verdict failed, in the two-word
 * vocabulary the `sandbox-lifecycle` capability names ("a tracker or runtime error during
 * evaluation"). It is what the dashboard's stall alarm names (`dashboard-page`, "Silent stall
 * becomes loud"), so an operator facing a run of no-verdict ticks knows whether to look at the
 * tracker or at the container runtime.
 *
 * <p>Implements NFR-O3 of add-serve-sandbox-lifecycle; D17 (F90) of tighten-spec-contract.
 */
public enum SkippedVerdictSource {
    /** The liveness oracle reached no verdict: a tracker outage left the object's ownership unknown. */
    TRACKER,
    /**
     * The container runtime failed the sweep: it refused the stop or dispose the matrix decided
     * on, or the object it listed could not be inspected or parsed.
     */
    RUNTIME
}
