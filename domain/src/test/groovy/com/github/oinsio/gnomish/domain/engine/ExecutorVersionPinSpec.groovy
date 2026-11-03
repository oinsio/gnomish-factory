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
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR13 of define-executor-contract (design D11 as amended): the executor version pin lives in the
 * task state, is written by the round writer in the round's own commit (first version wins), is
 * carried by every lifecycle rewrite as {@code totals} is, and the engine pre-flight compares it with
 * what the describe preflight obtained, escalating {@code PipelineMismatch} before any round. The
 * end-to-end spec on a bare origin is task 6.2's.
 */
class ExecutorVersionPinSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final def TRACE = new ToolTrace(new AttemptKey('TASK-1', 'build', 0), [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()

    EnginePorts ports(DescribedExecutors described) {
        new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), listener, persistence, VirtualTimeEquipment.on(new VirtualClock()),
                AttemptDelivery.assumedDelivered(), described, RoundIdentity.inPlace())
    }

    /** The executor each stage names (task 6.1): build runs acme, lint-stage runs lint, review none. */
    static final Map<String, String> NAMED = [build: 'acme', 'lint-stage': 'lint']

    static StageDefinition stage(String name) {
        def named = NAMED[name]
        def executor = named == null
                ? new StageDefinition.Executor(ExecutorType.API, 'model', [:])
                : new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), named)
        new StageDefinition(name, 'purpose', [], [], executor,
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(String... stages) {
        new PipelineDefinition('1', new AutonomyLimits(5), stages.collect {
            stage(it)
        })
    }

    static ExecutorVersions versions(Map<String, String> byName) {
        new ExecutorVersions(byName.collectEntries { k, v ->
            [
                k,
                UntrustedText.subprocess(v)
            ]
        })
    }

    /** acme described at {@code version}; the {@code build} stage names it. */
    static DescribedExecutors acmeOnBuild(String version) {
        new DescribedExecutors(versions(acme: version), ExecutorCredentialNames.none())
    }

    void passes(int rounds) {
        rounds.times {
            executor.scripted << ExecutionResult.completed(ExecutorUsage.none(), TRACE, [])
            builtinRunner.scripted << new Verdict.Pass()
        }
    }

    // FR13, D11: the first round that ran the executor pins its version, in that round's own commit
    def "the round commit of the executor's first round carries its version"() {
        given:
        passes(1)

        when:
        new Engine().run(pipeline('build'), CONTEXT, TaskState.atStageStart('build'), new FakeWorkspace(), ports(acmeOnBuild('1')))

        then:
        persistence.entries.size() == 1
        persistence.entries.first().state.executorVersions().of('acme').get().raw() == '1'
    }

    // FR13, D11: the pin survives an advancement, though the attempts list is emptied
    def "a later stage that does not run the executor still carries its pin"() {
        given:
        passes(2)

        when:
        new Engine().run(pipeline('build', 'review'), CONTEXT, TaskState.atStageStart('build'), new FakeWorkspace(), ports(acmeOnBuild('1')))

        then: 'the review round recorded only its own attempts and the pin from build'
        def review = persistence.entries.last().state
        review.position() instanceof Position.PipelineEnd
        review.attempts().size() == 1
        review.executorVersions().of('acme').get().raw() == '1'
    }

    // FR13: first version wins — a pinned executor keeps its version across further rounds
    def "a further round does not overwrite the pinned version"() {
        given:
        passes(1)
        def pinned = new TaskState(new Position.AtStage('lint-stage'), 0, [], UsageSnapshot.EMPTY, versions(acme: '1'))
        def described = new DescribedExecutors(versions(acme: '1', lint: '4'), ExecutorCredentialNames.none())

        when: 'a stage naming lint runs after acme was pinned'
        new Engine().run(pipeline('build', 'lint-stage'), CONTEXT, pinned, new FakeWorkspace(), ports(described))

        then:
        def pin = persistence.entries.last().state.executorVersions()
        pin.of('acme').get().raw() == '1'
        pin.of('lint').get().raw() == '4'
    }

    // FR13, D11: after a park and a human return (the decision reset), a changed describe escalates
    def "a changed describe after a decision reset escalates PipelineMismatch before any round"() {
        given: 'a task whose build round pinned acme at 1, then parked and was reset by a human return'
        passes(1)
        new Engine().run(pipeline('build', 'review'), CONTEXT, TaskState.atStageStart('build'), new FakeWorkspace(), ports(acmeOnBuild('1')))
        def reset = persistence.entries.last().state.resetAttempts()
        def written = persistence.entries.size()
        passes(1)

        when: 'the next run describes acme at 2'
        def outcome = new Engine().run(pipeline('build', 'review'), CONTEXT, reset, new FakeWorkspace(), ports(acmeOnBuild('2')))

        then: 'the outcome names the executor and both versions, and no round ran'
        outcome instanceof TaskOutcome.Escalated
        def cause = ((outcome as TaskOutcome.Escalated).report() as EscalationReport.PipelineMismatch).cause() as MismatchCause.ExecutorVersion
        cause.executor().raw() == 'acme'
        cause.recorded().raw() == '1'
        cause.current().raw() == '2'
        persistence.entries.size() == written
        executor.scripted.size() == 1
        (outcome as TaskOutcome.Escalated).finalState() == reset
    }

    // FR13: an agreeing describe, or none at all, runs the stage as before
    def "#row runs the stage"() {
        given:
        passes(1)
        def pinned = new TaskState(new Position.AtStage('build'), 0, [], UsageSnapshot.EMPTY, versions(acme: '1'))

        when:
        def outcome = new Engine().run(pipeline('build'), CONTEXT, pinned, new FakeWorkspace(), ports(described))

        then:
        outcome instanceof TaskOutcome.Completed
        persistence.entries.size() == 1
        persistence.entries.last().state.executorVersions() == versions(acme: '1')

        where:
        row | described
        'the same version' | acmeOnBuild('1')
        'no describe at all' | DescribedExecutors.none()
    }

    // FR13, D11: every lifecycle rewrite carries the pin unchanged, as totals
    def "#rewrite carries the pin"() {
        given:
        def pin = versions(acme: '1')
        def state = new TaskState(position, 2, [], UsageSnapshot.EMPTY, pin)

        expect:
        transition.call(state).executorVersions().is(pin)

        where:
        rewrite | position | transition
        'advanceTo' | new Position.AtStage('build') | { TaskState s ->
            s.advanceTo(new Position.AtStage('review'))
        }
        'resetAttempts' | new Position.AtStage('build') | { TaskState s ->
            s.resetAttempts()
        }
        'startOfStage' | new Position.AtStage('build') | { TaskState s ->
            s.startOfStage()
        }
        'approveGate' | new Position.AwaitingApproval('build') | { TaskState s ->
            s.approveGate(pipeline('build', 'review'))
        }
    }
}
