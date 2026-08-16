package com.github.oinsio.gnomish.domain.engine;

/**
 * The four token counts for one model within one accounting unit — an executor
 * round or a single judge vote. This is the smallest telemetry seam for future
 * budget enforcement: adapters report tokens here when they know them (NFR-C1).
 * Cache-creation and cache-read are tracked separately from input/output because
 * for agentic workloads cache reads dominate real cost; folding them together
 * would misrepresent cost by an order of magnitude either way (design D4).
 *
 * <p>Whether tokens are known at all — for a whole model, or for the round as a
 * whole — is the concern of the containing type ({@link ExecutorUsage#tokensByModel()},
 * {@link JudgeUsage#perVote()}): an empty map means unreported, never a fabricated
 * zero. All four counts are non-negative — a token count cannot go below zero;
 * zero is accepted since a round may legitimately consume no tokens of a kind.
 * Inert value data compared by content.
 *
 * <p>Implements FR5, D4 of add-agent-executor.
 *
 * @param input tokens consumed as input; never negative
 * @param output tokens produced as output; never negative
 * @param cacheCreation tokens spent writing to the prompt cache; never negative
 * @param cacheRead tokens served from the prompt cache; never negative
 */
public record TokenUsage(long input, long output, long cacheCreation, long cacheRead) {

    public TokenUsage {
        input = requireNonNegative(input, "input");
        output = requireNonNegative(output, "output");
        cacheCreation = requireNonNegative(cacheCreation, "cacheCreation");
        cacheRead = requireNonNegative(cacheRead, "cacheRead");
    }

    /**
     * Returns a new {@code TokenUsage} that is the field-wise sum of {@code this} and
     * {@code other} — the per-model merge step {@link ExecutorUsage#plus(ExecutorUsage)}
     * folds in for every shared model id (FR5, NFR-C1, design D4).
     *
     * @param other the usage to add into this one; never null
     */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                input + other.input, output + other.output,
                cacheCreation + other.cacheCreation, cacheRead + other.cacheRead);
    }

    /**
     * Fails fast on a negative count: a token count cannot be negative (NFR-C1).
     * Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the
     * 100% mutation gate.
     */
    private static long requireNonNegative(long value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("TokenUsage." + component + " must not be negative");
        }
        return value;
    }
}
