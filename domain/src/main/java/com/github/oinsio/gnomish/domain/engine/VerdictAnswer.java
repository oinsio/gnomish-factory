package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a verdict executor answered, as {@link VerdictDerivation} reads it (design D5 of
 * migrate-verify-to-executor-contract): a {@link Sarif} answer — the SARIF results of every run,
 * flattened in the order written, each with its {@link ResultLevel} — or a {@link Malformed} one,
 * the reason the contract's answer reading refused the document; or no answer at all, because the
 * executor reported a failed status: {@link QualityFailure} ({@code failed/quality}) or {@link
 * InfrastructureFailure} ({@code failed/infrastructure}). An answer carries no verdict: the
 * derivation decides that, so every check path hands the engine one of these and none constructs a
 * {@link Verdict} (D9).
 *
 * <p>This is the domain's own mirror of the contract's verdict answer, its malformed reading and its
 * failed status: {@code :domain} may reach JDK-only leafs only, so an adapter maps the contract's
 * reading onto this type. A malformed answer is a value here rather than an exception, so it can
 * never be lost on the way to the derivation and read as a pass (FR6 of define-executor-contract).
 *
 * <p>Implements FR7 of migrate-verify-to-executor-contract.
 */
public sealed interface VerdictAnswer {

    /**
     * A well-formed SARIF answer.
     *
     * @param results every result of every run, in the order written; defensively copied,
     *     unmodifiable, possibly empty
     * @param runUrl the authoritative platform run that produced the answer, carried into a pass
     *     (NFR-O2 of add-sandbox-core), or {@code null} when no platform run exists
     */
    record Sarif(List<Result> results, @Nullable String runUrl) implements VerdictAnswer {

        public Sarif {
            results = List.copyOf(results);
        }

        /**
         * An answer with no platform run to point at.
         *
         * @param results every result, in the order written; never null
         */
        public Sarif(List<Result> results) {
            this(results, null);
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
     * The executor reported {@code failed/quality} rather than a SARIF answer: the work was judged
     * and found wanting by a rule outside the levels (a command's non-zero exit until the {@code
     * shell} built-in reports SARIF, a poll that timed out under the quality timeout class, a
     * provider's failed run). It fails under every threshold.
     *
     * @param findings the problems the executor named; defensively copied, unmodifiable, possibly
     *     empty
     */
    record QualityFailure(List<Finding> findings) implements VerdictAnswer {

        public QualityFailure {
            findings = List.copyOf(findings);
        }
    }

    /**
     * The executor reported {@code failed/infrastructure}: no answer could be obtained (no
     * environment, a process that never started, an unreachable endpoint, an adapter that threw).
     * It is cannot-verify under every threshold, so no attempt is burned (FR4 of add-stage-engine).
     *
     * @param reason the short cause the escalation report names; never null, never blank
     * @param details what was captured beside it, typically a preserved stack trace; never null,
     *     may be empty
     */
    record InfrastructureFailure(UntrustedText reason, UntrustedText details) implements VerdictAnswer {}

    /**
     * One SARIF result: its level and the finding it states.
     *
     * @param level the result's level; never null
     * @param finding the result as a finding — message, location, details; never null
     */
    record Result(ResultLevel level, Finding finding) {}
}
