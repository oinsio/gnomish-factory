package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * Shared Spock fixture for the {@code TrackerTask} that {@code tracker.fetchTask} stubs return
 * across the take-pipeline specs, varying only the lifecycle state under test. Extracted because
 * an identical private {@code taskWith(TrackerTaskState)} helper was hand-duplicated across
 * {@code TakeEscalationExitSpec}, {@code TakeFinishReportSpec}, {@code TakePauseExitSpec},
 * {@code TakeParkRetrySpec}, {@code take.ClaimGuardSpec}, {@code take.SelfFencingBoundarySpec} and
 * {@code take.RevocationCheckingAttemptPersistenceSpec}.
 */
class TrackerTaskFixtures {

    static TrackerTask taskWith(TaskRef ref, TrackerTaskState state) {
        taskWith(ref, state, false)
    }

    static TrackerTask taskWith(TaskRef ref, TrackerTaskState state, boolean finished) {
        new TrackerTask(ref, new TaskSnapshot(ref.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body')), state, AbortFacts.none(), finished)
    }

    /**
     * A take order (introduce-take-order) for the task at {@code ref}, held by {@code instanceId}
     * and claimed through {@code tracker}: its {@code ref()} is {@code ref} and its {@code taskId()}
     * is {@code ref.id()}, the pair the take-pipeline specs passed by hand before the order bundled
     * them. {@code definition} is the startup pipeline; the default is a one-stage {@code build}.
     */
    static TakeOrder orderFor(TaskRef ref, Tracker tracker, InstanceId instanceId,
            PipelineDefinition definition = oneStagePipeline()) {
        def run = new RunOrder(Path.of('/tmp/gnomish-clone'), null, definition, RunArguments.InteractiveMode.NONE, false)
        new TakeOrder(run, taskWith(ref, new TrackerTaskState.Working(instanceId.value())), tracker, instanceId)
    }

    /** A one-stage {@code build} pipeline: enough for an order, never run by these specs. */
    static PipelineDefinition oneStagePipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }
}
