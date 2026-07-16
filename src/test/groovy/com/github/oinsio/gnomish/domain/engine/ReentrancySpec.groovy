package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * Reentrancy and no-retry gate, task 7.2 (NFR-R1, NFR-R2). The {@link Engine} holds no
 * static or shared mutable state, so one instance can drive many runs at once with
 * independent ports and never retries a port call.
 *
 * <p>Reentrancy (NFR-R1) is proved the hard way: two runs share a SINGLE {@code Engine}
 * instance and each verify-check runner blocks on a shared {@link CyclicBarrier} of two
 * parties, so neither run can leave its verify chain until BOTH are inside it — the runs
 * are provably in flight concurrently, not merely one-after-another. Each run carries its
 * own distinct {@code taskId} and its own fakes, and afterwards each fake and each event
 * stream is asserted to hold ONLY its own run's task — zero cross-talk — with both runs
 * reaching the identical terminal a solo run reaches.
 *
 * <p>No-retry (NFR-R2) rides on the same runs: every logical port invocation is scripted
 * with exactly one result and asserted to have been called exactly once. A single retried
 * port call would either exhaust a one-shot script (loud failure) or push a call count to
 * two — so the exact counts are the no-retry proof.
 *
 * <p>Implements NFR-R1, NFR-R2 of add-stage-engine.
 */
class ReentrancySpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(List<VerifyCheck> verify) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage([builtin('files_exist')])
        ])
    }

    static ExecutionResult.Completed completed(String taskId) {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey(taskId, 'build', 0), []))
    }

    /** One run's independent set of fakes plus its recorded outcome. */
    static class Run {
        String taskId
        ScriptedExecutor executor
        ScriptedBuiltinCheckRunner builtinRunner
        InMemoryAttemptPersistence persistence
        RecordingEventListener listener
        TaskOutcome outcome
    }

    Run newRun(String taskId, CyclicBarrier barrier) {
        def run = new Run(taskId: taskId)
        run.executor = new ScriptedExecutor([completed(taskId)])
        run.builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        if (barrier != null) {
            run.builtinRunner.onRun = { check, workspace -> barrier.await(5, TimeUnit.SECONDS) }
        }
        run.persistence = new InMemoryAttemptPersistence()
        run.listener = new RecordingEventListener()
        return run
    }

    EnginePorts portsOf(Run run) {
        def clock = new VirtualClock()
        new EnginePorts(run.executor, run.builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), run.listener,
                run.persistence, clock, new VirtualSleeper(clock))
    }

    void assertIsolatedSuccess(Run run) {
        // NFR-R1: the run reached the same terminal a solo run reaches — Completed at pipeline end
        assert run.outcome instanceof TaskOutcome.Completed
        assert run.outcome.finalState().position() instanceof Position.PipelineEnd
        assert run.outcome.finalState().attemptsUsed() == 0

        // NFR-R2: every logical port was invoked exactly once — no retried call
        assert run.executor.requests.size() == 1
        assert run.builtinRunner.calls.size() == 1
        assert run.persistence.entries.size() == 1

        // NFR-R1: no cross-talk — every recorded touch names ONLY this run's task
        assert run.executor.requests.every { it.context().taskId() == run.taskId }
        assert run.persistence.entries.every { it.taskId == run.taskId }
        assert run.listener.events.every { it.taskId() == run.taskId }
    }

    // NFR-R1/NFR-R2: two runs sharing one Engine, provably concurrent (each blocked on a
    //     shared two-party barrier inside its verify chain), finish isolated and identical
    //     to a solo run, each port called exactly once — no shared state, no retry.
    def "two concurrent runs on one Engine stay isolated and never retry a port"() {
        given: 'one shared engine and two runs with independent fakes and distinct task ids'
        def engine = new Engine()
        def barrier = new CyclicBarrier(2)
        def runA = newRun('TASK-A', barrier)
        def runB = newRun('TASK-B', barrier)

        when: 'both runs execute concurrently on virtual threads, meeting inside the verify chain'
        try (def pool = Executors.newVirtualThreadPerTaskExecutor()) {
            def futureA = pool.submit({ engine.run(pipeline(), new TaskContext('TASK-A', 't', 'b', []), TaskState.atStageStart('build'), WORKSPACE, portsOf(runA)) } as java.util.concurrent.Callable)
            def futureB = pool.submit({ engine.run(pipeline(), new TaskContext('TASK-B', 't', 'b', []), TaskState.atStageStart('build'), WORKSPACE, portsOf(runB)) } as java.util.concurrent.Callable)
            runA.outcome = futureA.get(10, TimeUnit.SECONDS)
            runB.outcome = futureB.get(10, TimeUnit.SECONDS)
        }

        then: 'each run is isolated, successful, and free of cross-talk or retries'
        assertIsolatedSuccess(runA)
        assertIsolatedSuccess(runB)

        and: 'the two event streams are disjoint by task — neither leaked into the other'
        runA.listener.events.every { it.taskId() == 'TASK-A' }
        runB.listener.events.every { it.taskId() == 'TASK-B' }
        (runA.listener.events*.taskId() as Set).intersect(runB.listener.events*.taskId() as Set).isEmpty()
    }

    // NFR-R1: a run driven ALONE on the same reused Engine instance produces the identical
    //     isolated result — the engine carries nothing between runs.
    def "the same Engine instance drives independent solo runs with no carryover"() {
        given: 'one engine reused across two sequential solo runs'
        def engine = new Engine()

        when: 'the first run completes'
        def first = newRun('TASK-A', null)
        first.outcome = engine.run(pipeline(), new TaskContext('TASK-A', 't', 'b', []),
        TaskState.atStageStart('build'), WORKSPACE, portsOf(first))

        and: 'a second run on the SAME engine follows'
        def second = newRun('TASK-B', null)
        second.outcome = engine.run(pipeline(), new TaskContext('TASK-B', 't', 'b', []),
        TaskState.atStageStart('build'), WORKSPACE, portsOf(second))

        then: 'both are isolated, identical successes with each port called exactly once'
        assertIsolatedSuccess(first)
        assertIsolatedSuccess(second)
    }
}
