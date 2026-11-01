package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import com.github.oinsio.gnomish.domain.engine.UsageFold;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The drain run's in-memory {@code runSummary} accumulator (design D6, FR13): a mutable,
 * thread-safe sink that {@code TakeSlotRunner} feeds beside {@code DrainReport#record} — the
 * same write point (design D6: "beside {@code DrainReport}"). {@code ServeShutdownWiring}
 * attaches a fresh instance only for a {@code --drain} run, mirroring {@code DrainReport}'s own
 * drain-only attachment; an ordinary {@code serve} run never attaches one, so nothing
 * accumulates and no {@code runSummary} line can ever be built for a standing stop (FR13).
 *
 * <p>Mirrors {@link TaskOutcomeLineAssembler}'s vocabulary derivation from {@link TakeResult}
 * (design D6): only the four variants carrying a {@code finalState} — {@code Delivered}, {@code
 * AwaitingHuman}, {@code Aborted}, {@code Revoked} — contribute; {@code EmptyQueue}/{@code
 * Skipped}/{@code InfrastructureUnavailable} are no-ops, matching "engine run happened iff spend
 * happened". The tokens are each task's usage snapshot per model, added across the tasks by the
 * one {@link UsageFold} (design D15 of define-executor-contract), never by a sum of this class's
 * own. {@link #counts()} and
 * {@link #tokensByModel()} are read once, at the {@code runSummary} write point, when the drain
 * run completes — the ledger is never read back to build them (design D5).
 *
 * <p>Implements FR13, D6 of add-serve-observability; FR17 of define-executor-contract.
 */
public final class RunSummaryAccumulator {

    private int delivered;
    private int awaitingHuman;
    private int aborted;
    private int revoked;
    private Map<String, TokenUsage> tokensByModel = Map.of();

    /**
     * Records {@code result}'s contribution to the run's totals, or does nothing for {@link
     * TakeResult.EmptyQueue}/{@link TakeResult.Skipped}/{@link TakeResult.InfrastructureUnavailable}/{@link
     * TakeResult.OperatorPreconditionUnmet}.
     * Safe to call from any number of concurrently-finishing slot threads.
     *
     * @param result the slot's terminal result; never null
     */
    public synchronized void record(TakeResult result) {
        TaskState finalState =
                switch (result) {
                    case TakeResult.Delivered r -> {
                        delivered++;
                        yield r.finalState();
                    }
                    case TakeResult.AwaitingHuman r -> {
                        awaitingHuman++;
                        yield r.finalState();
                    }
                    case TakeResult.Aborted r -> {
                        aborted++;
                        yield r.finalState();
                    }
                    case TakeResult.Revoked r -> {
                        revoked++;
                        yield r.finalState();
                    }
                    case TakeResult.EmptyQueue _,
                            TakeResult.Skipped _,
                            TakeResult.InfrastructureUnavailable _,
                            TakeResult.OperatorPreconditionUnmet _ -> null;
                };
        if (finalState == null) {
            return;
        }
        tokensByModel = UsageFold.addByModel(tokensByModel, UsageFold.byModel(finalState.totals()));
    }

    /** A snapshot of the outcome counters accumulated so far; never null. */
    public synchronized OutcomeCounts counts() {
        return new OutcomeCounts(delivered, awaitingHuman, aborted, revoked);
    }

    /** A snapshot of the token sums accumulated so far, keyed by model id; never null. */
    public synchronized Map<String, LedgerTokenUsage> tokensByModel() {
        var ledger = new LinkedHashMap<String, LedgerTokenUsage>();
        tokensByModel.forEach((model, usage) -> ledger.put(model, LedgerTokenUsage.of(usage)));
        return Map.copyOf(ledger);
    }
}
