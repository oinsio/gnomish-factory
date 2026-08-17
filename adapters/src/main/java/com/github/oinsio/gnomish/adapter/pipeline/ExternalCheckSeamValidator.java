package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates the seam around each {@code external} check's provider selection (FR6, FR13, UX1 of
 * add-plugin-architecture) — the manifest-side counterpart of {@link TrackerSeamValidator}. Core
 * owns the problem <em>around</em> the delegation, namely a check naming a provider no discovered
 * jar serves; the provider's own {@link CheckParamsValidator} owns everything inside its {@code
 * params}, which core never interprets.
 *
 * <p>The registry is keyed by every discovered provider, so its {@code keySet()} <em>is</em> the
 * discovered set: a provider grading no params contributes {@link CheckParamsValidator#none()}
 * rather than no entry, which is what lets a missing key mean "nobody serves this provider" and
 * nothing else.
 *
 * <p>Provider existence is checked at load time because it is in-process knowledge — which jars are
 * on the classpath — not target liveness (which the loader deliberately never probes, NG7 of
 * load-pipeline-config). It is checked identically whatever run mode follows, including manual run
 * whose interactive client replaces the whole external seam: a mode-dependent rule would let the
 * same manifest load in one mode and fail in another (design D10).
 *
 * <p>Errors are located {@link ConfigError} data in stage-then-check order, never thrown, so they
 * aggregate with every other load problem in the loader's single pass (NFR-R1).
 *
 * <p>Implements FR6, FR13, UX1 of add-plugin-architecture.
 */
public final class ExternalCheckSeamValidator {

    private ExternalCheckSeamValidator() {}

    /**
     * Validates every {@code external} check of every stage against the discovered providers.
     *
     * <p>Implements FR6, FR13, UX1 of add-plugin-architecture.
     *
     * @param stages the mapped stages in pipeline order
     * @param checkProviders the params validators keyed by every discovered provider; an empty map
     *     means no provider was discovered and every {@code external} check is reported unknown
     * @return every located problem, in stage-then-check-then-field order; empty when every check
     *     selects a discovered provider whose validator accepts its params
     */
    public static List<ConfigError> validate(
            List<StageDefinition> stages, Map<String, CheckParamsValidator> checkProviders) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            validateStage(stage, checkProviders, errors);
        }
        return List.copyOf(errors);
    }

    private static void validateStage(
            StageDefinition stage, Map<String, CheckParamsValidator> checkProviders, List<ConfigError> errors) {
        String manifest = "stages/%s/stage.yaml".formatted(stage.name());
        List<VerifyCheck> checks = stage.verify();
        for (int index = 0; index < checks.size(); index++) {
            if (checks.get(index) instanceof VerifyCheck.External external) {
                validateExternal(manifest, index, external, checkProviders, errors);
            }
        }
    }

    /**
     * Reports an undiscovered provider — naming both the selection and the discovered set, so an
     * operator reads what to install or what to write instead (UX1) — or hands the check's params
     * to the selected provider's own validator. A check whose provider is unknown is not also
     * params-graded: no validator can speak for a provider nobody serves.
     */
    private static void validateExternal(
            String manifest,
            int index,
            VerifyCheck.External external,
            Map<String, CheckParamsValidator> checkProviders,
            List<ConfigError> errors) {
        CheckParamsValidator validator = checkProviders.get(external.provider());
        if (validator == null) {
            errors.add(new ConfigError(
                    manifest,
                    "verify[%d].provider".formatted(index),
                    "unknown check provider '%s'; discovered providers: %s"
                            .formatted(external.provider(), checkProviders.keySet())));
            return;
        }
        errors.addAll(validator.validate(manifest, "verify[%d].params".formatted(index), external.params()));
    }
}
