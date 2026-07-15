package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure stage-level local-sanity rule (design D5a/D6): the catalog-free
 * checks over a stage's Mechanism and Quality Control sections that need no
 * live target (NG7). Per stage, in manifest section order:
 *
 * <ol>
 *   <li>the executor {@code model} must be non-blank — pinned in the manifest
 *       so any instance reproduces the stage identically, for every executor
 *       type ({@code api} and {@code agent-cli} alike), never left to a CLI
 *       default (FR11);</li>
 *   <li>the resolved attempt limit must be at least 1 (FR7);</li>
 *   <li>each {@code external} check must have a non-blank {@code checkId}, a
 *       positive {@code interval}, a positive {@code timeout}, and
 *       {@code interval <= timeout} (equal is valid) (FR11);</li>
 *   <li>each {@code judge} check must pin a non-blank {@code model} — the same
 *       reproducibility rationale as the executor model — and declare
 *       {@code votes} that are &ge; 1 and odd (FR11).</li>
 * </ol>
 *
 * <p>Restraint (no validation creep): {@code Builtin} and {@code Command}
 * checks carry no FR11 sanity rule and are never flagged — FR11 lists only
 * {@code external} and {@code judge}. Executor and judge {@code settings} are
 * an opaque pass-through map (Q1/D5a), accepted without inspecting keys,
 * values, or ranges. The {@code judge} {@code criteriaFile} existence is an
 * I/O-bound adapter concern (FR6, task 6.3), not checked here. Target liveness
 * — whether a model is real or a CI check exists — is deliberately not checked
 * (NG7).
 *
 * <p>Problems are located {@link ConfigError}s naming the stage manifest
 * (NFR-O1, UX2); verify-check errors are located by the check's index in the
 * stage's {@code verify} list. Nothing is thrown (design D3). Error order is
 * deterministic (NFR-R1): stages in pipeline order; within a stage the model
 * error, then the attempt-limit error, then verify-check errors in check-list
 * order and, within a check, field order.
 *
 * <p>Implements FR11 (and the FR7 attempt-limit clause) of load-pipeline-config.
 */
public final class StageSanityRule {

    private StageSanityRule() {}

    /**
     * Validates every stage's mechanism and check configs against the FR11 and
     * FR7 local-sanity rules: a sane stage yields no errors; each fault yields
     * exactly one located {@link ConfigError} per the contracts above.
     *
     * <p>Implements FR11 (and the FR7 attempt-limit clause) of
     * load-pipeline-config.
     *
     * @param stages the stages in exactly the {@code pipeline.yaml} declaration
     *     order, as carried by {@link PipelineDefinition#stages()}
     */
    public static List<ConfigError> validate(List<StageDefinition> stages) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            validateStage(stage, errors);
        }
        return List.copyOf(errors);
    }

    private static void validateStage(StageDefinition stage, List<ConfigError> errors) {
        String manifest = "stages/%s/stage.yaml".formatted(stage.name());
        if (stage.executor().model().isBlank()) {
            errors.add(
                    new ConfigError(
                            manifest,
                            "executor.model",
                            "missing required executor model; the model must be pinned in the manifest for every executor type"));
        }
        int attemptLimit = stage.limits().attemptLimit();
        if (attemptLimit < 1) {
            errors.add(new ConfigError(
                    manifest,
                    "attempts",
                    "non-positive resolved attempt limit %d; the resolved limit must be at least 1"
                            .formatted(attemptLimit)));
        }
        List<VerifyCheck> checks = stage.verify();
        for (int index = 0; index < checks.size(); index++) {
            validateCheck(manifest, index, checks.get(index), errors);
        }
    }

    private static void validateCheck(String manifest, int index, VerifyCheck check, List<ConfigError> errors) {
        switch (check) {
            case VerifyCheck.External external -> validateExternal(manifest, index, external, errors);
            case VerifyCheck.Judge judge -> validateJudge(manifest, index, judge, errors);
            case VerifyCheck.Builtin ignored -> {
                // No FR11 sanity rule for builtin checks (no validation creep).
            }
            case VerifyCheck.Command ignored -> {
                // No FR11 sanity rule for command checks (no validation creep).
            }
        }
    }

    private static void validateExternal(
            String manifest, int index, VerifyCheck.External external, List<ConfigError> errors) {
        if (external.checkId().isBlank()) {
            errors.add(new ConfigError(
                    manifest, "verify[%d].checkId".formatted(index), "missing required external check identifier"));
        }
        Duration interval = external.interval();
        Duration timeout = external.timeout();
        boolean intervalPositive = isPositive(interval);
        if (!intervalPositive) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].interval".formatted(index),
                    "non-positive external poll interval %s; the interval must be positive".formatted(interval)));
        }
        boolean timeoutPositive = isPositive(timeout);
        if (!timeoutPositive) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].timeout".formatted(index),
                    "non-positive external poll timeout %s; the timeout must be positive".formatted(timeout)));
        }
        if (intervalPositive && timeoutPositive && interval.compareTo(timeout) > 0) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].interval".formatted(index),
                    "external poll interval %s exceeds timeout %s; the interval must not exceed the timeout"
                            .formatted(interval, timeout)));
        }
    }

    private static void validateJudge(String manifest, int index, VerifyCheck.Judge judge, List<ConfigError> errors) {
        if (judge.model().isBlank()) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].model".formatted(index),
                    "missing required judge model; the model must be pinned in the manifest for reproducibility"));
        }
        int votes = judge.votes();
        if (votes < 1 || votes % 2 == 0) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].votes".formatted(index),
                    "invalid judge vote count %d; votes must be at least 1 and odd".formatted(votes)));
        }
    }

    private static boolean isPositive(Duration duration) {
        return !duration.isZero() && !duration.isNegative();
    }
}
