package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * What reading an answer document produced: the typed {@link Answer}, or the reason it is malformed.
 * A malformed answer is never a pass: the engine resolves it by the role it asked for (design D4 of
 * define-executor-contract), cannot-verify for a {@code verdict}, {@code failed/infrastructure}
 * otherwise.
 *
 * <p>Implements FR3, NFR-S2 of define-executor-contract.
 */
public sealed interface AnswerReading {

    /** The document is an answer of a known kind that holds its invariants. */
    record Parsed(Answer answer) implements AnswerReading {}

    /** The document is no answer; {@code reason} names what is wrong, quoting it, so it is a carrier. */
    record Malformed(UntrustedText reason) implements AnswerReading {}
}
