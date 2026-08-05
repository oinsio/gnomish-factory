package com.github.oinsio.gnomish.serveobservability;

import java.time.Duration;
import java.time.Instant;

/**
 * The pure mapping from a completed drain run's {@link RunSummaryAccumulator} totals to the
 * ledger's {@code runSummary} line (design D6, FR13). Reads {@link RunSummaryAccumulator#counts()}
 * and {@link RunSummaryAccumulator#tokensByModel()} once, at the point the run's window is known
 * — never the ledger (design D5).
 *
 * <p>Stateless: a pure function with no fields, following the module's assembler convention (e.g.
 * {@link TaskOutcomeLineAssembler}, {@link LifecycleLineAssembler}).
 *
 * <p>Implements FR13, D6 of add-serve-observability.
 */
public final class RunSummaryLineAssembler {

    private RunSummaryLineAssembler() {}

    /**
     * Assembles the drain run's single {@link RunSummaryLine}.
     *
     * @param instance the writing process's identity; never null
     * @param startedAt when the drain run started; never null
     * @param finishedAt when the drain run finished; never null (must not precede {@code
     *     startedAt})
     * @param accumulator the run's in-memory accumulator, read at this call; never null
     * @return the assembled line
     */
    public static RunSummaryLine assemble(
            InstanceInfo instance, Instant startedAt, Instant finishedAt, RunSummaryAccumulator accumulator) {
        return new RunSummaryLine(
                instance,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                accumulator.counts(),
                accumulator.tokensByModel());
    }
}
