package com.github.oinsio.gnomish.adapter.law

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * Shared test helper for {@link PipelineLawReaderSpec} and {@link GitObjectsPipelineLawReaderSpec}:
 * both build a single-stage {@link PipelineDefinition} around one instructions reference and its
 * judge checks, and need nothing else from the definition to exercise {@link PipelineLawReader}.
 */
final class PipelineDefinitionFixtures {

    private PipelineDefinitionFixtures() {
    }

    static PipelineDefinition pipeline(StageDefinition stage) {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    static StageDefinition stage(String instructionsRef, List<VerifyCheck> checks) {
        new StageDefinition(
                'implement',
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-opus', [:]),
                instructionsRef,
                checks,
                new AutonomyLimits(3),
                AdvancementMode.AUTO)
    }
}
