package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One check in a stage's ordered {@code verify} list — the Quality Control section
 * of the stage contract ({@code .claude/rules/stage-description.md} §6), modeled as
 * one of four sealed variants (design D5) so validators and the future engine can
 * switch exhaustively. Checks are inert data: nothing here is executed (NG1) —
 * order within a stage is preserved by the stage's verify list (task 3.5).
 *
 * <p>Local-sanity rules over these fields — non-blank identifiers, {@code external}
 * timing positivity and {@code interval <= timeout}, {@code judge} votes &ge; 1 and
 * odd — are checked by the pure validators as located {@link ConfigError}s (design
 * D6, task 4.4), never by throwing constructors: a throwing constructor would
 * destroy an invalid value before the validator could see and report it.
 *
 * <p>Implements FR2 of load-pipeline-config.
 */
public sealed interface VerifyCheck {

    /**
     * A built-in declarative check implemented by the engine (e.g. {@code files_exist},
     * schema validation), addressed by name with opaque declarative params. Like
     * executor settings (D5a), params are plain JDK types (String/Number/Boolean/
     * List/Map) so the domain stays Jackson-free; their keys and values belong to
     * the engine check, not to this model.
     *
     * <p>Implements FR2 of load-pipeline-config.
     *
     * @param name the engine check identifier (e.g. {@code files_exist})
     * @param params the check's declarative parameters, possibly empty; immutable
     */
    record Builtin(String name, Map<String, Object> params) implements VerifyCheck {

        public Builtin {
            params = Map.copyOf(params);
        }
    }

    /**
     * An arbitrary executable check: any command line, contract "exit code 0 =
     * pass". The exit-code semantics live in the future stage engine — here the
     * command is carried as data only (NG1, NFR-S1).
     *
     * <p>Carries the sandbox freshness knob {@code verifyIn} (FR13 of
     * add-sandbox-core): {@link VerifyIn#SAME_BOX} (the default) runs the check
     * in the round environment; {@link VerifyIn#FRESH_BOX} runs it in a new
     * environment materialized from the attempt commit, proving the branch is
     * self-sufficient. Applying the knob is the engine/adapter's concern; here
     * it is inert data.
     *
     * <p>Implements FR2 of load-pipeline-config; FR13 of add-sandbox-core (the
     * {@code verifyIn} field).
     *
     * @param command the command line to execute
     * @param verifyIn where the check runs relative to the round environment
     *     (FR13 of add-sandbox-core); never {@code null}
     */
    record Command(String command, VerifyIn verifyIn) implements VerifyCheck {

        /**
         * Convenience constructor for callers that declare no {@code verifyIn} —
         * every call site predating add-sandbox-core keeps compiling unchanged,
         * defaulting to {@link VerifyIn#SAME_BOX} (same-box verification, FR13).
         *
         * <p>Implements FR13 of add-sandbox-core.
         */
        public Command(String command) {
            this(command, VerifyIn.SAME_BOX);
        }
    }

    /**
     * Where a {@link Command} check runs relative to the round environment
     * (design D8, FR13 of add-sandbox-core). {@link #SAME_BOX} is the default —
     * the check reuses the round environment, unchanged prior behavior.
     * {@link #FRESH_BOX} materializes a new environment from the attempt commit
     * so uncommitted or out-of-branch work cannot influence the verdict;
     * applying the knob is an engine/adapter concern, out of scope for this
     * model.
     *
     * <p>Implements FR13 of add-sandbox-core.
     */
    enum VerifyIn {
        SAME_BOX,
        FRESH_BOX
    }

    /**
     * An asynchronous third-party verification polled for a result (e.g. a CI check
     * on the task branch, a SonarQube quality gate). Timing sanity — positive
     * {@code interval}/{@code timeout} with {@code interval <= timeout} — and the
     * non-blank identifier are FR11 validator concerns (task 4.4), carried here
     * unvalidated.
     *
     * <p>Implements FR2 and carries the FR11 fields of load-pipeline-config.
     *
     * @param checkId the external check identifier the poller looks up (e.g. a CI
     *     check name); liveness of the target is deliberately not validated (NG7)
     * @param interval how often to poll for the verdict
     * @param timeout how long to poll before giving up (a quality failure by default)
     * @param timeoutClass how a poll timeout at {@code timeout} classifies —
     *     {@link TimeoutClass#QUALITY} (default) burns a stage attempt,
     *     {@link TimeoutClass#INFRASTRUCTURE} does not (FR9); classification
     *     itself is an engine concern, out of scope here
     * @param pinPaths the law-declared pin paths — repo paths whose content
     *     defines the check (workflow files, analyzer configs, local actions),
     *     in declaration order (FR16 of add-sandbox-core); the pin-check guard
     *     unions them with adapter-contributed paths and byte-compares against
     *     the base branch before any adapter contact. These are repo-relative
     *     <em>data</em>, never read by the loader, so they are exempt from the
     *     {@code .gnomish/} traversal rule (pointing at {@code
     *     .github/workflows/ci.yml} is their normal use); only their lexical
     *     form (no absolute, no {@code .}/{@code ..} segments) is validated
     *     (task 2.4). Possibly empty; immutable
     */
    record External(
            String checkId, Duration interval, Duration timeout, TimeoutClass timeoutClass, List<String> pinPaths)
            implements VerifyCheck {

        public External {
            pinPaths = List.copyOf(pinPaths);
        }

        /**
         * Convenience constructor for callers that declare no pin paths — every
         * call site predating add-sandbox-core keeps compiling unchanged, with
         * {@link #pinPaths()} defaulting to empty (the adapter's own contributed
         * paths still apply, FR16 of add-sandbox-core).
         *
         * <p>Implements FR16 of add-sandbox-core.
         */
        public External(String checkId, Duration interval, Duration timeout, TimeoutClass timeoutClass) {
            this(checkId, interval, timeout, timeoutClass, List.of());
        }
    }

    /**
     * How an {@link External} check's poll timeout classifies when the poll
     * deadline is reached without a verdict (design D7). {@link #QUALITY} is the
     * default — a timeout burns a stage attempt, unchanged prior behavior.
     * {@link #INFRASTRUCTURE} marks a timeout as unable to verify rather than a
     * quality failure — no attempt burned, escalated instead (stage-description.md
     * §7).
     *
     * <p>Implements FR9 of add-external-check-github-actions.
     */
    enum TimeoutClass {
        QUALITY,
        INFRASTRUCTURE
    }

    /**
     * An LLM-as-judge verification via the executor port: acceptance criteria plus
     * pinned model settings, yielding a structured verdict. The {@code votes}
     * rule (&ge; 1 and odd) is an FR11 validator concern (task 4.4), carried here
     * unvalidated.
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
