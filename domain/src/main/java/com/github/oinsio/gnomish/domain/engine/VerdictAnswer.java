package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * What a verdict executor answered, as {@link VerdictDerivation} reads it (design D5 of
 * migrate-verify-to-executor-contract): a {@link Sarif} answer — the SARIF results of every run,
 * flattened in the order written, each with its {@link ResultLevel} — or a {@link Malformed} one,
 * the reason the contract's answer reading refused the document. An answer carries no pass or
 * fail: the derivation decides that against the check's threshold.
 *
 * <p>This is the domain's own mirror of the contract's verdict answer and its malformed reading:
 * {@code :domain} may reach JDK-only leafs only, so an adapter maps the contract's reading onto
 * this type. A malformed answer is a value here rather than an exception, so it can never be lost
 * on the way to the derivation and read as a pass (FR6 of define-executor-contract).
 *
 * <p>Implements FR7 of migrate-verify-to-executor-contract.
 */
public sealed interface VerdictAnswer {

    /**
     * A well-formed SARIF answer.
     *
     * @param results every result of every run, in the order written; defensively copied,
     *     unmodifiable, possibly empty
     */
    record Sarif(List<Result> results) implements VerdictAnswer {

        public Sarif {
            results = List.copyOf(results);
        }
    }

    /**
     * An answer document the contract's reading refused.
     *
     * @param reason what is wrong with the document, quoting it, in the family of its writer;
     *     never null
     */
    record Malformed(UntrustedText reason) implements VerdictAnswer {}

    /**
     * One SARIF result: its level and the finding it states.
     *
     * @param level the result's level; never null
     * @param finding the result as a finding — message, location, details; never null
     */
    record Result(ResultLevel level, Finding finding) {}
}
