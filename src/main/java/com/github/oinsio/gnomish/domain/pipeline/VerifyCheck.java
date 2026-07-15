package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;
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
     * <p>Implements FR2 of load-pipeline-config.
     *
     * @param command the command line to execute
     */
    record Command(String command) implements VerifyCheck {}

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
     */
    record External(String checkId, Duration interval, Duration timeout) implements VerifyCheck {}

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
