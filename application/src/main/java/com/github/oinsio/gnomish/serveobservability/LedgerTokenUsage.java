package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.domain.engine.TokenUsage;

/**
 * The four token counts for one model within a ledger record's {@code
 * tokensByModel} map — mirrors {@code domain.engine.TokenUsage} field for
 * field, as a distinct class in this package (matching the {@code
 * status.json}/{@code usage.json} precedent of a package-local copy rather
 * than an import across module boundaries: this package must not pull in
 * the engine module's wiring, following {@link InstanceInfo}'s task 1.1
 * rationale).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR11, FR13 of add-serve-observability.
 *
 * @param input tokens consumed as input; never negative
 * @param output tokens produced as output; never negative
 * @param cacheCreation tokens spent writing to the prompt cache; never negative
 * @param cacheRead tokens served from the prompt cache; never negative
 */
public record LedgerTokenUsage(long input, long output, long cacheCreation, long cacheRead) {

    public LedgerTokenUsage {
        input = requireNonNegative(input, "input");
        output = requireNonNegative(output, "output");
        cacheCreation = requireNonNegative(cacheCreation, "cacheCreation");
        cacheRead = requireNonNegative(cacheRead, "cacheRead");
    }

    /**
     * Converts the engine's {@link TokenUsage} field-for-field, the single shared conversion
     * point for both {@code RunSummaryAccumulator} and {@code TaskOutcomeLineAssembler}.
     */
    public static LedgerTokenUsage of(TokenUsage usage) {
        return new LedgerTokenUsage(usage.input(), usage.output(), usage.cacheCreation(), usage.cacheRead());
    }

    /**
     * Fails fast on a negative count. Kept as a shared static method rather
     * than inline in the compact constructor: PIT's record filter suppresses
     * all mutations inside a record's canonical constructor, which would
     * silently exempt this validation from the 100% mutation gate.
     */
    private static long requireNonNegative(long value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("LedgerTokenUsage." + component + " must not be negative");
        }
        return value;
    }
}
