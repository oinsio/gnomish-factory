package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;
import java.util.LinkedHashMap;
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
        public static final String FILES_EXIST = BuiltInExecutors.FILES_EXIST;

        /** The built-in a {@code command} check maps to. */
        public static final String SHELL = BuiltInExecutors.SHELL;

        /** The built-in an {@code external} check without a {@code provider} maps to. */
        public static final String GITHUB_ACTIONS = BuiltInExecutors.GITHUB_ACTIONS;

        /** The built-in reader of named verdicts on the attempt SHA; it needs {@code expected}. */
        public static final String SCM_STATUS = BuiltInExecutors.SCM_STATUS;

        public Executor {
            settings = Map.copyOf(settings);
            pinPaths = List.copyOf(pinPaths);
            expected = List.copyOf(expected);
        }

        /**
         * True when this check is polled over time: every {@code external} check, and every
         * check naming an executor other than the two in-process built-ins, so it carries the
         * poll timing, identifier and pin-path rules of the load validation (FR1, UX2 of
         * migrate-verify-to-executor-contract).
         */
        public boolean polled() {
            return token == Token.EXTERNAL
                    || (token != Token.JUDGE && !(executorName.equals(FILES_EXIST) || executorName.equals(SHELL)));
        }
    }

    /**
     * The manifest form an {@link Executor} check was declared with: one of the three
     * sugar tokens, the explicit {@code executor:} form, or a {@code judge} check as its
     * check round sees it ({@link Judge#asExecutorCheck()}).
     *
     * <p>Implements FR1, UX2 of migrate-verify-to-executor-contract; FR4 of
     * extract-agent-round-runner.
     */
    enum Token {
        BUILTIN,
        COMMAND,
        EXTERNAL,
        EXECUTOR,
        JUDGE
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
     * An LLM-as-judge verification: acceptance criteria plus the executor block that does the
     * judging — the very {@link StageDefinition.Executor} a stage's {@code executor:} declares,
     * mapped by the one block mapper and judged by the same rules (design D12 of
     * extract-agent-round-runner). The check has no {@code model}, {@code settings} or {@code
     * votes} of its own: who judges, with which model and settings, is the block's, and a second
     * opinion is a second {@code judge} check (design D2).
     *
     * <p>Implements FR2 of load-pipeline-config; FR4, FR13, UX1 of extract-agent-round-runner.
     *
     * @param criteriaFile path of the acceptance-criteria file, relative to the
     *     {@code .gnomish/} root (a plain string — the domain never touches the
     *     filesystem, D1; existence is checked by the adapter, FR6)
     * @param executor the check's executor block: its type, pinned model, settings and, for a
     *     {@code program}, the declaration it names; never null
     */
    record Judge(String criteriaFile, StageDefinition.Executor executor) implements VerifyCheck {

        /**
         * The judge as the check-round chain reads every check (design D2 of
         * extract-agent-round-runner): one {@code verdict} request to the executor its block
         * resolves to ({@link StageDefinition.Executor#registryName()}), with the block's settings
         * and its pinned model under {@link ExecutorSettings#MODEL_KEY} when it names one, the
         * criteria file as the check's identifier — the text the request's role input is rendered
         * from — the default threshold, no pin paths, no poll timing and a fresh box. One type for
         * both check kinds, so the chain has no judge arm of its own.
         *
         * <p>Implements FR4, M3 of extract-agent-round-runner.
         *
         * @return the check round's view of this judge; never null
         */
        public Executor asExecutorCheck() {
            Map<String, Object> settings = new LinkedHashMap<>(executor.settings());
            if (!executor.model().isBlank()) {
                settings.put(ExecutorSettings.MODEL_KEY, executor.model());
            }
            return new Executor(
                    Token.JUDGE,
                    executor.registryName(),
                    criteriaFile,
                    settings,
                    Duration.ZERO,
                    Duration.ZERO,
                    TimeoutClass.QUALITY,
                    List.of(),
                    VerifyIn.FRESH_BOX,
                    Threshold.DEFAULT,
                    List.of());
        }
    }
}
