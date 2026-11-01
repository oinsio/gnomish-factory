package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What no longer matches when a {@link EscalationReport.PipelineMismatch} is raised: a closed set, so
 * every render and every wire mapper switches exhaustively. Each text is a carrier because the
 * recorded half is read back off a task branch some instance wrote (design D3, NG4 of
 * type-untrusted-text), and each is required non-blank: a report that names nothing tells the
 * operator nothing to fix.
 *
 * <p>Implements FR9, FR10 of add-stage-engine; FR13 of define-executor-contract.
 */
public sealed interface MismatchCause {

    /**
     * The recorded position names a stage the pipeline no longer declares (FR9 of add-stage-engine).
     *
     * @param stage the stale stage name; never blank
     */
    record StaleStage(UntrustedText stage) implements MismatchCause {

        public StaleStage {
            stage = requireNonBlank(stage, "MismatchCause.StaleStage.stage");
        }
    }

    /**
     * An executor whose describe version differs from the version the task pinned at its first use
     * (FR13 of define-executor-contract, design D11).
     *
     * @param executor the executor's law name; never blank
     * @param recorded the version the task's pin carries; never blank
     * @param current the version this run's describe preflight obtained; never blank
     */
    record ExecutorVersion(UntrustedText executor, UntrustedText recorded, UntrustedText current)
            implements MismatchCause {

        public ExecutorVersion {
            executor = requireNonBlank(executor, "MismatchCause.ExecutorVersion.executor");
            recorded = requireNonBlank(recorded, "MismatchCause.ExecutorVersion.recorded");
            current = requireNonBlank(current, "MismatchCause.ExecutorVersion.current");
        }
    }

    /**
     * Fails fast on a blank text; a static method, not inline, because PIT's record filter would
     * exempt a guard in a canonical constructor from the mutation gate.
     */
    private static UntrustedText requireNonBlank(UntrustedText value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(component + " must not be blank");
        }
        return value;
    }
}
