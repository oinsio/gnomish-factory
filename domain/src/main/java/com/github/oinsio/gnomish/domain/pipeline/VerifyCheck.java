package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One check in a stage's ordered {@code verify} list — the Quality Control section
 * of the stage contract ({@code .claude/rules/stage-description.md} §6), modeled as
 * two sealed variants (design D1 of migrate-verify-to-executor-contract): an
 * {@link Executor} check, which every {@code builtin}, {@code command}, {@code external}
 * and {@code executor:} manifest entry maps to, and the {@link Judge}. Checks are inert
 * data: nothing here is executed — order within a stage is preserved by the stage's
 * verify list.
 *
 * <p>Local-sanity rules over these fields are checked by the pure validators as
 * located {@link ConfigError}s, never by throwing constructors: a throwing constructor
 * would destroy an invalid value before the validator could see and report it.
 *
 * <p>Implements FR2 of load-pipeline-config; FR1 of migrate-verify-to-executor-contract.
 */
public sealed interface VerifyCheck {

    /**
     * A {@code verdict} request to a named executor (design D1 of
     * migrate-verify-to-executor-contract). The manifest tokens are sugar the loader maps
     * to executor names: {@code builtin} to {@link #FILES_EXIST}, {@code command} to
     * {@link #SHELL}, {@code external} to its provider's executor (default {@link
     * #GITHUB_ACTIONS}); {@code executor: <name>} names one directly. Settings are plain
     * JDK types (String/Number/Boolean/List/Map) so the domain stays Jackson-free.
     *
     * <p>Implements FR1, UX2 of migrate-verify-to-executor-contract; FR13, FR16 of
     * add-sandbox-core (the {@code verifyIn} and {@code pinPaths} fields); FR9 of
     * add-external-check-github-actions (the {@code timeoutClass} field).
     *
     * @param token the manifest form the check was declared with
     * @param executorName the executor the check is a request to; blank only when an
     *     {@code executor:} entry names none, which the sanity rule reports
     * @param checkId the check's identifier: a {@code builtin}'s name, a {@code command}'s
     *     command line, an {@code external} or {@code executor:} check's {@code checkId}
     * @param settings the executor's per-check settings (a {@code builtin}'s or {@code
     *     external}'s {@code params}), possibly empty; immutable
     * @param interval how often a polled check polls; {@link Duration#ZERO} when undeclared
     * @param timeout how long a polled check polls; {@link Duration#ZERO} when undeclared
     * @param timeoutClass how a poll timeout classifies — {@link TimeoutClass#QUALITY}
     *     (default) burns a stage attempt, {@link TimeoutClass#INFRASTRUCTURE} does not
     * @param pinPaths the law-declared pin paths, repo-relative data in declaration
     *     order; possibly empty; immutable
     * @param verifyIn where the check runs relative to the round environment
     * @param threshold the lowest result level that fails the check (default {@link
     *     Threshold#ERROR})
     * @param expected the context names an {@code scm-status} check waits for; possibly
     *     empty; immutable
     */
    record Executor(
            Token token,
            String executorName,
            String checkId,
            Map<String, Object> settings,
            Duration interval,
            Duration timeout,
            TimeoutClass timeoutClass,
            List<String> pinPaths,
            VerifyIn verifyIn,
            Threshold threshold,
            List<String> expected)
            implements VerifyCheck {

        /** The built-in a {@code builtin} check maps to. */
        public static final String FILES_EXIST = "files-exist";

        /** The built-in a {@code command} check maps to. */
        public static final String SHELL = "shell";

        /** The built-in an {@code external} check without a {@code provider} maps to. */
        public static final String GITHUB_ACTIONS = "github-actions";

        /** The built-in reader of named verdicts on the attempt SHA; it needs {@code expected}. */
        public static final String SCM_STATUS = "scm-status";

        /**
         * The check-provider key the {@code github-actions} executor is still registered
         * under by the bundle's check SPI and keyed by in {@code factory.check.*}, until
         * the executor registry dispatch replaces the provider registry (tasks 3.3, 5.1 of
         * migrate-verify-to-executor-contract).
         */
        private static final String GITHUB_PROVIDER_KEY = "github";

        public Executor {
            settings = Map.copyOf(settings);
            pinPaths = List.copyOf(pinPaths);
            expected = List.copyOf(expected);
        }

        /**
         * True when the engine polls this check through the external-check client: every
         * {@code external} check, and every check naming an executor other than the two
         * in-process built-ins. The bridge the provider-registry dispatch reads until the
         * executor registry runs every check (task 5.1 of
         * migrate-verify-to-executor-contract).
         */
        public boolean polled() {
            return token == Token.EXTERNAL || !(executorName.equals(FILES_EXIST) || executorName.equals(SHELL));
        }

        /**
         * The key the check-provider registry and the {@code factory.check.*} subtree know
         * this check's executor by: {@code github} for {@link #GITHUB_ACTIONS}, the executor
         * name otherwise. Deleted with the provider registry (tasks 3.3, 5.1 of
         * migrate-verify-to-executor-contract).
         */
        public String providerKey() {
            return executorName.equals(GITHUB_ACTIONS) ? GITHUB_PROVIDER_KEY : executorName;
        }
    }

    /**
     * The manifest form an {@link Executor} check was declared with: one of the three
     * sugar tokens, or the explicit {@code executor:} form.
     *
     * <p>Implements FR1, UX2 of migrate-verify-to-executor-contract.
     */
    enum Token {
        BUILTIN,
        COMMAND,
        EXTERNAL,
        EXECUTOR
    }

    /**
     * Where a check runs relative to the round environment (design D8, FR13 of
     * add-sandbox-core). {@link #SAME_BOX} is the default — the check reuses the round
     * environment. {@link #FRESH_BOX} materializes a new environment from the attempt
     * commit so uncommitted or out-of-branch work cannot influence the verdict.
     *
     * <p>Implements FR13 of add-sandbox-core.
     */
    enum VerifyIn {
        SAME_BOX,
        FRESH_BOX
    }

    /**
     * How a polled check's timeout classifies when the poll deadline is reached without
     * a verdict (design D7). {@link #QUALITY} is the default — a timeout burns a stage
     * attempt. {@link #INFRASTRUCTURE} marks a timeout as unable to verify — no attempt
     * burned, escalated instead (stage-description.md §7).
     *
     * <p>Implements FR9 of add-external-check-github-actions.
     */
    enum TimeoutClass {
        QUALITY,
        INFRASTRUCTURE
    }

    /**
     * An LLM-as-judge verification via the executor port: acceptance criteria plus
     * pinned model settings, yielding a structured verdict. The {@code votes} rule
     * (&ge; 1 and odd) is a validator concern, carried here unvalidated.
     *
     * <p>Implements FR2 and carries the FR11 fields of load-pipeline-config.
     *
     * @param criteriaFile path of the acceptance-criteria file, relative to the
     *     {@code .gnomish/} root (a plain string — the domain never touches the
     *     filesystem, D1; existence is checked by the adapter, FR6)
     * @param model the judge model, pinned for reproducibility (FR11)
     * @param settings opaque model settings as plain JDK types (D5a), possibly
     *     empty; immutable
     * @param votes how many judge votes to collect (majority verdict)
     */
    record Judge(String criteriaFile, String model, Map<String, Object> settings, int votes) implements VerifyCheck {

        public Judge {
            settings = Map.copyOf(settings);
        }
    }
}
