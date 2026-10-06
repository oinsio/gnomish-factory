package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Path
import spock.lang.Specification

/**
 * {@link ManualRunAssembly#assemble} wiring assertions that need no engine round and so no agent
 * subprocess: the optional heartbeat {@code extraListener} (task 6.1 of add-claim-heartbeat) joined to the engine event composite. Kept apart
 * from {@code ManualRunAssemblySpec}, whose features drive the real fake-agent binary, so these
 * fast checks feed the mutation gate directly. The external-check-client wiring assertions that
 * used to live in this file moved to {@code ManualRunAssemblyCheckClientWiringSpec} — a separate
 * capability, per {@code .claude/rules/testing.md}.
 *
 * <p>Implements FR11 of add-claim-heartbeat (extra-listener wiring).
 */
class ManualRunAssemblyWiringSpec extends Specification implements AppAssemblyFixture {

    private static StageDefinition stage(String name, int attemptLimit) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                [],
                new AutonomyLimits(attemptLimit),
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition() {
        new PipelineDefinition('1', new AutonomyLimits(7), [stage('build', 4)])
    }

    private def assemble(TaskState initialState, EngineEventListener extraListener = null) {
        def assembly = extraListener == null ? newAssembly() : newAssembly().withExtraListener(extraListener)
        assembly.assemble(
                new RunOrder(Path.of('').toAbsolutePath(), null, definition(), false),
                context('task-1'),
                initialState,
                new InMemoryAttemptPersistence(),
                [],
                // No round runs in this spec, so the law source is never read; any binding suffices.
                LawBinding.workingTree(Path.of('').toAbsolutePath()))
    }

    // FR11 of add-claim-heartbeat: a supplied extra listener (the take run's HeartbeatProgress) is
    //     joined to the engine event composite, so every event the engine emits reaches it too.
    def "an extra engine listener is fanned every event through the wired composite"() {
        given:
        def listener = Mock(EngineEventListener)
        def event = new EngineEvent.AttemptStarted(new AttemptKey('task-1', 'build', 0))

        when:
        def run = assemble(TaskState.atStageStart('build'), listener)
        run.ports().listener().onEvent(event)

        then:
        1 * listener.onEvent(event)
    }

    // FR11: with no extra listener supplied, the composite carries only the default listeners — a
    //     null extraListener is never added, so no spurious observer is wired.
    def "no extra listener is wired when none is supplied"() {
        given:
        def event = new EngineEvent.AttemptStarted(new AttemptKey('task-1', 'build', 0))

        when: 'assembling without an extra listener and firing an event does not fail'
        def run = assemble(TaskState.atStageStart('build'))
        run.ports().listener().onEvent(event)

        then:
        noExceptionThrown()
    }
}
