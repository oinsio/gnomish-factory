package com.github.oinsio.gnomish.domain.pipeline.fixture

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * Builds the stage a verify chain runs for specs that drive the chain directly: a {@code build}
 * stage whose verify list is the checks the spec names, nothing else of it read by the chain but the
 * stage a check request carries (design D21 of migrate-verify-to-executor-contract: the chain takes
 * the stage, not the bare list, so a check round's request can name it).
 */
final class VerifyingStages {

    private VerifyingStages() {}

    /** The {@code build} stage verifying {@code checks} in order. */
    static StageDefinition of(List<? extends VerifyCheck> checks) {
        new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'instructions.md', checks as List<VerifyCheck>, new AutonomyLimits(3), AdvancementMode.AUTO)
    }
}
