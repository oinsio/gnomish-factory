package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.engine.DescribedExecutors;
import com.github.oinsio.gnomish.domain.engine.ExecutorCredentialNames;
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the describe preflight decided (design D5 of define-executor-contract, as revised
 * 2026-10-31): the answers obtained, from which the engine receives the versions and the program
 * dispatch builds each declared program's stage executor, or the located errors that park the task
 * as an invalid law does — one shape for a program that could not answer and for an answer that
 * disagrees with the law, so no second classifier exists.
 *
 * <p>Implements FR4, FR9, FR13, UX1 of define-executor-contract.
 */
public sealed interface DescribeOutcome {

    /**
     * Every program executor answered and agrees with the law.
     *
     * @param answers the describe answer of every described executor, by its law name, in declaration
     *     order; never null, possibly empty
     */
    record Described(Map<String, Describe> answers) implements DescribeOutcome {

        public Described {
            answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }

        /** What the engine receives of these answers: the version and credential names per executor. */
        public DescribedExecutors executors() {
            return executorsOf(answers);
        }

        /**
         * The one derivation of the engine's value from the answers (task 7.3 of
         * define-executor-contract): the describe's version, and the describe's own credential names,
         * which the agreement has shown equal to the law's grant (NFR-S1).
         *
         * @param answers the describe answer per executor name; never null
         * @return the versions and credential names; never null
         */
        public static DescribedExecutors executorsOf(Map<String, Describe> answers) {
            Map<String, UntrustedText> versions = new LinkedHashMap<>();
            Map<String, List<String>> credentials = new LinkedHashMap<>();
            answers.forEach((name, describe) -> {
                versions.put(name, describe.version());
                credentials.put(name, describe.credentials());
            });
            return new DescribedExecutors(new ExecutorVersions(versions), new ExecutorCredentialNames(credentials));
        }
    }

    /**
     * A program could not be described, or its answer disagrees with the law.
     *
     * @param errors the located errors, in declaration order; never empty
     */
    record Invalid(List<ConfigError> errors) implements DescribeOutcome {

        public Invalid {
            errors = List.copyOf(errors);
        }
    }
}
