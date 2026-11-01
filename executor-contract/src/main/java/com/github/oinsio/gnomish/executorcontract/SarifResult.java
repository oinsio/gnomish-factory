package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * One result of a SARIF run, reduced to what the engine reads: the rule, the level, the message
 * text, and the first location's artifact URI and start line when the result names them. The rule,
 * message and URI are the executor's text, each a carrier (NFR-S2); they are shown, never parsed.
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
public record SarifResult(
        @Nullable UntrustedText ruleId,
        SarifLevel level,
        UntrustedText message,
        @Nullable UntrustedText uri,
        @Nullable Integer startLine) {

    /** Refuses a start line below 1. */
    public SarifResult {
        WireChecks.positiveOrAbsent(startLine, "sarif result startLine");
    }
}
