package com.github.oinsio.gnomish.domain.engine;

/**
 * Input and output token counts for one accounting unit — an executor round or a
 * single judge vote. This is the smallest telemetry seam for future budget
 * enforcement: adapters report tokens here when they know them (NFR-C1).
 *
 * <p>The type always holds both numbers; whether tokens are known at all is the
 * concern of the containing type, which holds a {@code @Nullable TokenUsage}
 * (design D5). Both counts are non-negative — a token count cannot go below zero;
 * zero is accepted since a round may legitimately consume no tokens. Inert value
 * data compared by content.
 *
 * <p>Implements FR13, NFR-C1 of add-stage-engine.
 *
 * @param inputTokens tokens consumed as input; never negative
 * @param outputTokens tokens produced as output; never negative
 */
public record TokenUsage(long inputTokens, long outputTokens) {

    public TokenUsage {
        inputTokens = requireNonNegative(inputTokens, "inputTokens");
        outputTokens = requireNonNegative(outputTokens, "outputTokens");
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
