package com.github.oinsio.gnomish.sandbox

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * Shared minimal, valid {@link StageDefinition} builder for segment-related
 * specs (design D8, FR12/FR13 of add-sandbox-core): a stage identified only by
 * name, optionally marked {@code requires-fresh}, used to exercise segment
 * boundary logic without repeating the full stage construction in every spec.
 */
trait StageFixture {

    StageDefinition stage(String name, boolean requiresFresh = false) {
        new StageDefinition(
                name, "Purpose of $name",
                [new ArtifactInput.Source()], [
                    new ArtifactOutput("$name-out")
                ],
                new StageDefinition.Executor(
                        ExecutorType.AGENT_CLI, 'claude-sonnet-4-5', [:], new Sandbox([], requiresFresh)),
                "stages/$name/instructions.md",
                [
                    new VerifyCheck.Command('./gradlew check')
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }
}
