package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.Threshold;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Maps a stage's ordered {@code verify} DTO list into the sealed domain {@link VerifyCheck}
 * variants (task 5.3, design D2/D5), preserving check order. Every {@code builtin}, {@code
 * command}, {@code external} and {@code executor:} entry becomes one {@link
 * VerifyCheck.Executor}; the tokens are sugar for executor names (design D1 of
 * migrate-verify-to-executor-contract): {@code builtin} → {@code files-exist}, {@code command}
 * → {@code shell}, {@code external} → its {@code provider}, with an absent provider and the
 * legacy {@code github} both → {@code github-actions}. The token itself is kept on the check.
 *
 * <p>Raw {@code interval}/{@code timeout} strings become {@link Duration}s via {@link
 * DurationConfig#parse}: a malformed string is a located {@link ConfigError} appended to the
 * caller's list (so {@link PipelineMapper} aborts the definition), an absent one is {@link
 * Duration#ZERO}, which {@code StageSanityRule} flags on a polled check. The enum knobs are
 * parsed by {@link VerifyCheckKnobs}. An {@code external} or {@code executor:} check naming a
 * law executor granted credentials runs in a fresh box unless it declares otherwise (NFR-S1 of
 * migrate-verify-to-executor-contract, design D6); a {@code builtin} or {@code command} check names a
 * built-in, which a law never declares, so its default stays {@code same-box}. Opaque {@code params}/{@code settings} flow through as
 * defensive plain-JDK copies via {@link OpaqueSettings#copy}. Whether a named executor exists,
 * and whether its settings are well-formed, belongs to the load seam that holds the registry
 * ({@link ExternalCheckSeamValidator}), not here.
 *
 * <p>Implements FR2, FR11, D2, D5, D5a of load-pipeline-config; FR9 of
 * add-external-check-github-actions; FR13, FR16 of add-sandbox-core; FR7, FR13 of
 * add-plugin-architecture; FR1, UX2, NFR-S1 of migrate-verify-to-executor-contract.
 */
final class VerifyCheckMapper {

    /**
     * The executor an {@code external} check names when its manifest names no provider (FR13, M4
     * of add-plugin-architecture) — recorded in the model, so the load seam grades it, never a
     * silent guess at dispatch.
     */
    static final String DEFAULT_PROVIDER = VerifyCheck.Executor.GITHUB_ACTIONS;

    /**
     * The check SPI's provider name for GitHub Actions, mapped to {@code github-actions} so legacy
     * manifests load unchanged (UX2 of migrate-verify-to-executor-contract).
     */
    static final String LEGACY_GITHUB_PROVIDER = "github";

    private VerifyCheckMapper() {}

    /**
     * Maps every verify DTO in order, appending each located error to {@code errors}, which makes
     * {@link PipelineMapper} discard the definition.
     *
     * @param manifest the stage manifest path, stamped into any located error
     * @param checks the ordered verify DTOs, or {@code null} when the stage declares none
     * @param credentialed the law's executor declarations granted credentials, by name (NFR-S1)
     * @param errors the caller's mutable error accumulator (FR8 aggregation)
     * @return the mapped checks in declaration order; empty when {@code checks} is {@code null}
     */
    static List<VerifyCheck> mapAll(
            String manifest,
            @Nullable List<VerifyCheckDto> checks,
            Set<String> credentialed,
            List<ConfigError> errors) {
        if (checks == null) {
            return List.of();
        }
        List<VerifyCheck> mapped = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            mapped.add(mapCheck(manifest, index, checks.get(index), credentialed, errors));
        }
        return mapped;
    }

    private static VerifyCheck mapCheck(
            String manifest, int index, VerifyCheckDto dto, Set<String> credentialed, List<ConfigError> errors) {
        return switch (dto) {
            case VerifyCheckDto.Builtin builtin ->
                inProcess(
                        VerifyCheck.Token.BUILTIN,
                        VerifyCheck.Executor.FILES_EXIST,
                        PipelineMapper.orEmpty(builtin.name()),
                        OpaqueSettings.copy(manifest, "verify[%d].params".formatted(index), builtin.params(), errors),
                        VerifyCheck.VerifyIn.SAME_BOX);
            case VerifyCheckDto.Command command ->
                inProcess(
                        VerifyCheck.Token.COMMAND,
                        VerifyCheck.Executor.SHELL,
                        PipelineMapper.orEmpty(command.command()),
                        Map.of(),
                        VerifyCheckKnobs.verifyIn(manifest, index, command.verifyIn(), false, errors));
            case VerifyCheckDto.External external -> mapExternal(manifest, index, external, credentialed, errors);
            case VerifyCheckDto.Executor executor -> mapExecutor(manifest, index, executor, credentialed, errors);
            case VerifyCheckDto.Judge judge ->
                new VerifyCheck.Judge(
                        PipelineMapper.orEmpty(judge.criteriaFile()),
                        PipelineMapper.orEmpty(judge.model()),
                        OpaqueSettings.copy(manifest, "verify[%d].settings".formatted(index), judge.settings(), errors),
                        judge.votes() == null ? 0 : judge.votes());
        };
    }

    /**
     * A {@code builtin} or {@code command} check: an in-process built-in with no polling, so its
     * timing is {@link Duration#ZERO}, which no sanity rule reads for it.
     */
    private static VerifyCheck inProcess(
            VerifyCheck.Token token,
            String executorName,
            String checkId,
            Map<String, Object> settings,
            VerifyCheck.VerifyIn verifyIn) {
        return new VerifyCheck.Executor(
                token,
                executorName,
                checkId,
                settings,
                Duration.ZERO,
                Duration.ZERO,
                VerifyCheck.TimeoutClass.QUALITY,
                List.of(),
                verifyIn,
                Threshold.DEFAULT,
                List.of());
    }

    private static VerifyCheck mapExternal(
            String manifest,
            int index,
            VerifyCheckDto.External external,
            Set<String> credentialed,
            List<ConfigError> errors) {
        String executorName = mapProvider(external.provider());
        Duration interval =
                DurationConfig.parse(manifest, "verify[%d].interval".formatted(index), external.interval(), errors);
        Duration timeout =
                DurationConfig.parse(manifest, "verify[%d].timeout".formatted(index), external.timeout(), errors);
        VerifyCheck.TimeoutClass timeoutClass =
                VerifyCheckKnobs.timeoutClass(manifest, index, external.timeoutClass(), errors);
        return new VerifyCheck.Executor(
                VerifyCheck.Token.EXTERNAL,
                executorName,
                PipelineMapper.orEmpty(external.checkId()),
                OpaqueSettings.copy(manifest, "verify[%d].params".formatted(index), external.params(), errors),
                interval,
                timeout,
                timeoutClass,
                copy(external.pinPaths()),
                VerifyCheckKnobs.verifyIn(manifest, index, null, credentialed.contains(executorName), errors),
                Threshold.DEFAULT,
                List.of());
    }

    /**
     * The explicit {@code executor: <name>} form (FR1 of migrate-verify-to-executor-contract): the
     * name is carried verbatim — whether an executor of that name exists, and whether its settings
     * fit its schema, is registry knowledge the mapper does not hold.
     */
    private static VerifyCheck mapExecutor(
            String manifest,
            int index,
            VerifyCheckDto.Executor executor,
            Set<String> credentialed,
            List<ConfigError> errors) {
        String executorName = PipelineMapper.orEmpty(executor.executor());
        return new VerifyCheck.Executor(
                VerifyCheck.Token.EXECUTOR,
                executorName,
                PipelineMapper.orEmpty(executor.checkId()),
                OpaqueSettings.copy(manifest, "verify[%d].settings".formatted(index), executor.settings(), errors),
                DurationConfig.parse(manifest, "verify[%d].interval".formatted(index), executor.interval(), errors),
                DurationConfig.parse(manifest, "verify[%d].timeout".formatted(index), executor.timeout(), errors),
                VerifyCheckKnobs.timeoutClass(manifest, index, executor.timeoutClass(), errors),
                copy(executor.pinPaths()),
                VerifyCheckKnobs.verifyIn(
                        manifest, index, executor.verifyIn(), credentialed.contains(executorName), errors),
                VerifyCheckKnobs.threshold(manifest, index, executor.threshold(), errors),
                copy(executor.expected()));
    }

    /**
     * The executor a {@code provider} names (FR13 of add-plugin-architecture): absent or the legacy
     * {@code github} is {@link #DEFAULT_PROVIDER}, any other carried verbatim for the load seam.
     */
    private static String mapProvider(@Nullable String provider) {
        return provider == null || provider.equals(LEGACY_GITHUB_PROVIDER) ? DEFAULT_PROVIDER : provider;
    }

    /** A declared list (pin paths, expected contexts) as a defensive copy; absent maps to empty. */
    private static List<String> copy(@Nullable List<String> declared) {
        return declared == null ? List.of() : List.copyOf(declared);
    }
}
