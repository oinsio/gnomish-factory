package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * Result of loading a {@code .gnomish/} configuration tree (design D3): either a
 * valid {@link PipelineDefinition} or the complete list of located problems.
 * Validation failure is data, never an exception (UX1) — exceptions are reserved
 * for genuine I/O faults such as an unreadable file.
 *
 * <p>Implements FR8 of load-pipeline-config.
 */
public sealed interface LoadOutcome {

    /**
     * The configuration is valid and fully loaded.
     *
     * <p>Implements FR8 of load-pipeline-config.
     *
     * @param definition the validated, immutable pipeline model
     */
    record Loaded(PipelineDefinition definition) implements LoadOutcome {}

    /**
     * The configuration is invalid; carries the complete set of problems found in
     * one pass, in aggregation order (UX1).
     *
     * <p>Implements FR8 of load-pipeline-config.
     *
     * @param errors all located validation problems; non-empty and immutable
     */
    record Invalid(List<ConfigError> errors) implements LoadOutcome {

        public Invalid {
            errors = requireNonEmptyCopy(errors);
        }

        /**
         * Defensively copies into an immutable list and rejects emptiness: FR8
         * requires a <em>non-empty</em> error list — an invalid outcome with
         * nothing to fix would be contradictory. Static rather than inline in the
         * compact constructor because PIT's record filter suppresses all
         * canonical-constructor mutations, which would exempt this logic from the
         * 100% mutation gate.
         */
        private static List<ConfigError> requireNonEmptyCopy(List<ConfigError> errors) {
            List<ConfigError> copy = List.copyOf(errors);
            if (copy.isEmpty()) {
                throw new IllegalArgumentException("LoadOutcome.Invalid requires at least one ConfigError");
            }
            return copy;
        }
    }
}
