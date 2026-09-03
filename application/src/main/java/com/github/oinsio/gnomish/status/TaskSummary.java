package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.TokenUsage;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The canonical task summary's facts (design D3, FR3 of harden-logging-observability): the one
 * value {@link AnchorLog#taskSummary} renders, whichever mode produced it.
 *
 * <p>Deliberately neutral — plain types only, no {@code TakeResult}, no {@code ParkReason}, no
 * ledger record. Two assemblers with genuinely different fact sources populate it (the serve/take
 * mapper over a terminal result, the manual run's accumulator over the engine's event stream), and
 * a value that named either source would force the other to depend on it. The neutrality is what
 * makes "one renderer for all modes" possible rather than aspirational.
 *
 * <p>Not to be confused with the ledger plane's {@code serveobservability.RunSummaryLine}: that
 * one records a whole drain <em>run</em> as machine-readable JSON. This is the log plane's
 * per-<em>task</em> line — see the glossary entry <em>canonical task summary</em>.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR3 of harden-logging-observability.
 *
 * @param outcome which terminal family the task left the factory in; never null
 * @param parkReason why the task was parked, when the outcome is {@link Outcome#AWAITING_HUMAN};
 *     null otherwise
 * @param stage the pipeline stage the task was in when it finished; null at pipeline end
 * @param attemptsUsed stage attempts consumed; never negative
 * @param wall wall-clock duration of the work; never negative
 * @param tokensByModel token usage keyed by resolved model id; empty when unreported (never a
 *     fabricated zero — {@link TokenUsage}'s own rule)
 */
public record TaskSummary(
        Outcome outcome,
        @Nullable String parkReason,
        @Nullable String stage,
        int attemptsUsed,
        Duration wall,
        Map<String, TokenUsage> tokensByModel) {

    public TaskSummary {
        if (attemptsUsed < 0) {
            throw new IllegalArgumentException("TaskSummary.attemptsUsed must not be negative");
        }
        if (wall.isNegative()) {
            throw new IllegalArgumentException("TaskSummary.wall must not be negative");
        }
        tokensByModel = Map.copyOf(tokensByModel);
    }

    /**
     * The terminal families a task can leave the factory in, and the reader reaction each one
     * warrants (ADR 0004's level policy): a delivery and a park are lifecycle anchors an operator
     * reads after the fact; an abort and a revocation are states an operator should look at.
     *
     * <p>The set mirrors the ledger's {@code taskOutcome} vocabulary rather than the engine's,
     * because the summary describes how the task left the <em>factory</em> — including the
     * outcomes that happen outside an engine run (a revoked claim, a quarantined branch parked
     * before any attempt is spent).
     */
    public enum Outcome {
        /** The pipeline finished and the work was delivered. */
        DELIVERED("delivered", false),
        /** The task is parked for a human — escalation, checkpoint, or an infrastructure fix. */
        AWAITING_HUMAN("awaitingHuman", false),
        /** The run ended on an infrastructure abort. */
        ABORTED("aborted", true),
        /** The claim was lost or withdrawn while the run held it. */
        REVOKED("revoked", true);

        private final String word;
        private final boolean worthLookingAt;

        Outcome(String word, boolean worthLookingAt) {
            this.word = word;
            this.worthLookingAt = worthLookingAt;
        }

        /**
         * The outcome as it is written in the summary line.
         *
         * @return the outcome word; never null
         */
        public String word() {
            return word;
        }

        /**
         * Whether an operator should look at this outcome — the WARN/INFO split of the summary
         * line, per ADR 0004's "levels are the reader's required reaction".
         *
         * @return true when the summary line is logged at WARN, false when it is an INFO anchor
         */
        public boolean worthLookingAt() {
            return worthLookingAt;
        }
    }
}
