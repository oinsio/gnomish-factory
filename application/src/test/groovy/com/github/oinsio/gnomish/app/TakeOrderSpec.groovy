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
import spock.lang.Specification

/**
 * FR3, D2 of introduce-take-order: the take order is the one place the claimed task's identity is
 * derived. The task's canonical ref and its snapshot id are deliberately different strings here,
 * so a derivation that read the wrong one cannot pass.
 */
class TakeOrderSpec extends Specification {

    static final TaskRef REF = new TaskRef('github:owner/repo#42')

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition(
                'plan', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    private TakeOrder orderFor(TrackerTask task) {
        def run = new RunOrder(Path.of('/clone'), null, pipeline(), RunArguments.InteractiveMode.NONE, false)
        new TakeOrder(run, task, Stub(Tracker), new InstanceId('gnomish', 'ab12cd'))
    }

    private static TrackerTask task() {
        new TrackerTask(
                REF,
                new TaskSnapshot('PROJ-42', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(),
                AbortFacts.none(),
                false)
    }

    def "FR3: ref() is the claimed task's canonical ref"() {
        expect:
        orderFor(task()).ref() == REF
    }

    def "FR3: taskId() is the id the claimed task's snapshot records, not the ref's id"() {
        given:
        def task = task()

        expect:
        orderFor(task).taskId() == 'PROJ-42'
        orderFor(task).taskId() == task.snapshot().id()
    }

    def "D6: withDefinition re-binds only the definition, keeping the task, the tracker, the identity and every other run field"() {
        given:
        def task = task()
        def tracker = Stub(Tracker)
        def instance = new InstanceId('gnomish', 'ab12cd')
        def startup = pipeline()
        def run = new RunOrder(Path.of('/clone'), 'release/1.2', startup, RunArguments.InteractiveMode.ALL, true)
        def order = new TakeOrder(run, task, tracker, instance)
        def taskDefinition = new PipelineDefinition('2', new AutonomyLimits(5), pipeline().stages())

        when:
        def bound = order.withDefinition(taskDefinition)

        then:
        bound.run().definition().is(taskDefinition)
        bound.run() == new RunOrder(Path.of('/clone'), 'release/1.2', taskDefinition, RunArguments.InteractiveMode.ALL, true)
        bound.trackerTask().is(task)
        bound.tracker().is(tracker)
        bound.instanceId().is(instance)

        and: 'the order it was derived from still carries the startup definition'
        order.run().definition().is(startup)
    }
}
