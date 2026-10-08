package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * The two pipelines whose first run parks, shared by every spec that drives a {@code gnomish run}
 * into a park and back (design D8 of make-run-headless): the park-recording specs of both media and
 * their kill-point rows. One home, so the stage names a spec resumes at are the ones it started from.
 */
final class ParkPipelines {

    private ParkPipelines() {}

    /**
     * One stage whose only check never passes: the first round exhausts the one-attempt limit, so the
     * run ends — and every resume of it ends again — on an {@code AttemptsExhausted} escalation.
     */
    static PipelineDefinition escalating(String stageName = 'build') {
        new PipelineDefinition('1', new AutonomyLimits(1), [
            stage(stageName, AdvancementMode.AUTO, [
                new VerifyCheck.Builtin('files_exist', [files: ['never-written.txt']])
            ])
        ])
    }

    /** Two manual checkpoints, so a resume past the first stops at the second. */
    static PipelineDefinition pausing(String first = 'build', String second = 'deploy') {
        new PipelineDefinition('1', new AutonomyLimits(1), [
            stage(first, AdvancementMode.MANUAL, []),
            stage(second, AdvancementMode.MANUAL, [])
        ])
    }

    /**
     * One manual checkpoint as the last stage: its approval, not its pass, writes {@code PipelineEnd}
     * (design D1 of make-checkpoint-gate-durable).
     */
    static PipelineDefinition lastGate(String stageName = 'build') {
        new PipelineDefinition('1', new AutonomyLimits(1), [
            stage(stageName, AdvancementMode.MANUAL, [])
        ])
    }

    /**
     * One automatic stage with no checks, for a gnome that asks (the fake agent's {@code
     * decision-needed}): every round parks on a {@code DecisionNeeded} escalation.
     */
    static PipelineDefinition asking(String stageName = 'build') {
        new PipelineDefinition('1', new AutonomyLimits(1), [
            stage(stageName, AdvancementMode.AUTO, [])
        ])
    }

    private static StageDefinition stage(String name, AdvancementMode advancement, List<VerifyCheck> checks) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', checks, new AutonomyLimits(1), advancement)
    }
}
