package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The constraints the schema files state on single values, checked where a DTO is built: a
 * {@code minLength: 1} string, a {@code minimum} integer, a {@code minItems} array, an environment
 * variable name.
 *
 * <p>Implements FR1 of define-executor-contract; FR2 of migrate-verify-to-executor-contract.
 */
final class WireChecks {

    private WireChecks() {}

    static void nonBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static void nonBlankEach(List<String> values, String field) {
        for (String value : values) {
            nonBlank(value, field);
        }
    }

    static void nonBlank(UntrustedText value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static void nonBlankTexts(List<UntrustedText> values, String field) {
        for (UntrustedText value : values) {
            nonBlank(value, field);
        }
    }

    static void nonBlankOrAbsent(@Nullable String value, String field) {
        if (value != null) {
            nonBlank(value, field);
        }
    }

    static void atLeast(@Nullable Long value, long minimum, String field) {
        if (value != null && value < minimum) {
            throw new IllegalArgumentException(field + " must be at least " + minimum + ", was " + value);
        }
    }

    /**
     * Whether {@code millis} lies outside the range a wait hint takes, 0 to {@link
     * ExecutorResult#MAX_POLL_AFTER_MILLIS} (design D19 of migrate-verify-to-executor-contract); here
     * rather than on the record, whose methods the mutation engine cannot redefine.
     */
    static boolean outOfHintRange(long millis) {
        return millis < 0 || millis > ExecutorResult.MAX_POLL_AFTER_MILLIS;
    }

    static void positiveOrAbsent(@Nullable Integer value, String field) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(field + " must be at least 1, was " + value);
        }
    }

    static void atLeastItems(List<?> items, int minimum, String field) {
        if (items.size() < minimum) {
            throw new IllegalArgumentException(field + " holds " + items.size() + " items; at least " + minimum
                    + " are required, " + (minimum - items.size()) + " missing");
        }
    }

    static void nonNegative(@Nullable BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative, was " + value);
        }
    }

    static void variableName(String value, String field) {
        if (!VariableNames.isVariableName(value)) {
            throw new IllegalArgumentException(field + " is not an environment variable name: " + value);
        }
    }

    static void variableNameEach(List<String> values, String field) {
        for (String value : values) {
            variableName(value, field);
        }
    }
}
