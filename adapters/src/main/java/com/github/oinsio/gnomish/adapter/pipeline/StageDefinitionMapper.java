package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode;
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput;
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput;
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import com.github.oinsio.gnomish.domain.pipeline.Sandbox;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps a stage's inputs, outputs, executor (including its {@code sandbox}
 * block), advancement mode and attempt-limit override into their domain
 * shapes. Extracted from {@link PipelineMapper} for file size; the behavior is
 * unchanged.
 *
 * <p>Implements FR7, FR11, D2, D5a of load-pipeline-config; FR12, FR13 of
 * add-sandbox-core (sandbox mapping).
 */
final class StageDefinitionMapper {

    private StageDefinitionMapper() {}

    static List<ArtifactInput> mapInputs(@Nullable List<ArtifactInputDto> inputs) {
        if (inputs == null) {
            return List.of();
        }
        List<ArtifactInput> mapped = new ArrayList<>();
        for (ArtifactInputDto input : inputs) {
            mapped.add(
                    switch (input) {
                        case ArtifactInputDto.Source ignored -> new ArtifactInput.Source();
                        case ArtifactInputDto.Internal internal ->
                            new ArtifactInput.Internal(PipelineMapper.orEmpty(internal.producerOutputId()));
                    });
        }
        return mapped;
    }

    static List<ArtifactOutput> mapOutputs(@Nullable List<ArtifactOutputDto> outputs) {
        if (outputs == null) {
            return List.of();
        }
        List<ArtifactOutput> mapped = new ArrayList<>();
        for (ArtifactOutputDto output : outputs) {
            mapped.add(new ArtifactOutput(PipelineMapper.orEmpty(output.id())));
        }
        return mapped;
    }

    static StageDefinition.Executor mapExecutor(@Nullable ExecutorDto executor) {
        if (executor == null) {
            return new StageDefinition.Executor(ExecutorType.API, "", Map.of());
        }
        return new StageDefinition.Executor(
                mapExecutorType(executor.type()),
                PipelineMapper.orEmpty(executor.model()),
                PipelineMapper.copySettings(executor.settings()),
                mapSandbox(executor.sandbox()));
    }

    /**
     * Maps the Mechanism's {@code sandbox} block into the domain {@link Sandbox}
     * (FR12, FR13 of add-sandbox-core): {@code null}/absent maps to
     * {@link Sandbox#none()} (no needs, segment-reuse default), an absent
     * {@code needs} to an empty list, and an absent {@code requiresFresh} to
     * {@code false}. The tighten-only {@code binding} field is deliberately not
     * carried into the domain — a repo-declared binding is a violation
     * {@code StructuralValidation} already reported (FR14), never a domain value.
     */
    private static Sandbox mapSandbox(@Nullable SandboxDto sandbox) {
        if (sandbox == null) {
            return Sandbox.none();
        }
        List<String> needs = sandbox.needs() == null ? List.of() : List.copyOf(sandbox.needs());
        return new Sandbox(needs, Boolean.TRUE.equals(sandbox.requiresFresh()));
    }

    /** Total on structurally-valid input (task 5.2 guaranteed a known value); absent maps to API. */
    private static ExecutorType mapExecutorType(@Nullable String type) {
        return "agent-cli".equals(type) ? ExecutorType.AGENT_CLI : ExecutorType.API;
    }

    /** Total on structurally-valid input (task 5.2 guaranteed a known value); absent maps to AUTO. */
    static AdvancementMode mapAdvancement(@Nullable String advancement) {
        return "manual".equals(advancement) ? AdvancementMode.MANUAL : AdvancementMode.AUTO;
    }

    static AutonomyLimits resolveLimits(int defaultLimit, @Nullable AutonomyDto autonomy) {
        Integer override = autonomy == null ? null : autonomy.attemptLimit();
        return AutonomyLimits.resolve(defaultLimit, override);
    }
}
