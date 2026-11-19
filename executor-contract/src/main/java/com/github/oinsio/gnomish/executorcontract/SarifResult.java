package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * One result of a SARIF run, reduced to what the engine reads: the rule, the level, the message
 * text, the first location's artifact URI and start line when the result names them, and the
 * details the result carries apart from its message in its property bag ({@code
 * properties.details}, design D19 of migrate-verify-to-executor-contract) — a log tail, a run link.
 * The rule, message, URI and details are the executor's text, each a carrier (NFR-S2); they are
 * shown, never parsed.
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract; FR5, FR6 of
 * migrate-verify-to-executor-contract.
 */
public record SarifResult(
        @Nullable UntrustedText ruleId,
        SarifLevel level,
        UntrustedText message,
        @Nullable UntrustedText uri,
        @Nullable Integer startLine,
        @Nullable UntrustedText details) {

    /** Refuses a start line below 1. */
    public SarifResult {
        WireChecks.positiveOrAbsent(startLine, "sarif result startLine");
    }

    /** A result that carries no details beside its message. */
    public SarifResult(
            @Nullable UntrustedText ruleId,
            SarifLevel level,
            UntrustedText message,
            @Nullable UntrustedText uri,
            @Nullable Integer startLine) {
        this(ruleId, level, message, uri, startLine, null);
    }
}
