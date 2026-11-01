package com.github.oinsio.gnomish.app;

import java.util.Map;

/**
 * The parsed command line of one {@code gnomish executor verify} invocation, produced by {@link
 * ExecutorVerifyArgumentsParser} (FR12 of define-executor-contract). Public because the kit's
 * command lives in its own package of the composition root.
 *
 * <p>Implements FR12 of define-executor-contract.
 *
 * @param program the candidate program, as the operator named it; never blank
 * @param pendingSettings the settings under which the candidate goes pending, as plain values;
 *     never null, empty when {@code --pending-settings} was not given
 */
public record ExecutorVerifyArguments(String program, Map<String, Object> pendingSettings) {

    public ExecutorVerifyArguments {
        if (program.isBlank()) {
            throw new IllegalArgumentException("the candidate program must not be blank");
        }
        pendingSettings = Map.copyOf(pendingSettings);
    }
}
