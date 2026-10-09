package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * Shared inputs of the two mechanics checkpoint specs (FR3, FR7 of make-checkpoint-gate-durable):
 * a pinned pipeline whose first stage is {@code manual}, and a state held at that stage's gate with
 * the passing round still in its history.
 */
final class CheckpointMechanicsFixtures {

    private CheckpointMechanicsFixtures() {}

    /** {@code build} (manual) then {@code test} (auto): the gate after {@code build} opens onto {@code test}. */
    static PipelineDefinition gatedPipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('build', AdvancementMode.MANUAL),
            stage('test', AdvancementMode.AUTO)
        ])
    }

    /** A state held at the gate of {@code stage}, its passing round recorded. */
    static TaskState gatedAt(String stage) {
        def passed = new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(),
        JudgeUsage.none(), [], Stop.none())
        new TaskState(new Position.AwaitingApproval(stage), 1, [passed], ExecutorUsage.none())
    }

    static TaskContext context() {
        new TaskContext('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    }

    private static StageDefinition stage(String name, AdvancementMode mode) {
        new StageDefinition(name, 'purpose', [], [], new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [], new AutonomyLimits(1), mode)
    }
}
