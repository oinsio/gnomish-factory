package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * FR14, FR21 of define-executor-contract (design D13): the engine stamps every recorded round with
 * its identity — the task, the stage that ran it and the round number from the round's key, and
 * the round token taken once from the run's {@link RoundIdentity} — so a reader never infers it.
 * A round the engine does not record takes no token.
 */
class RecordIdentitySpec extends EventEmissionSpecBase {

    /** A run's round identity yielding the given tokens in order, counting the takes. */
    static final class TokenSequence implements RoundIdentity {
        final List<RoundToken> tokens
        int taken

        TokenSequence(List<RoundToken> tokens) {
            this.tokens = tokens
        }

        @Override
        Optional<RoundToken> takeToken() {
            Optional.of(tokens[taken++])
        }
    }

    static final RoundToken FIRST = RoundToken.of('1111111111111111111111111111111111111111')
    static final RoundToken SECOND = RoundToken.of('2222222222222222222222222222222222222222')

    EnginePorts portsWith(RoundIdentity identity) {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                listener, persistence, VirtualTimeEquipment.on(clock, sleeper), identity)
    }

    // FR14: a pass on the first round advances the position in the same commit; the record still
    //     names the stage that ran it
    // Scenario: pipeline/stage-engine / The record names its stage
    def "FR14: a first-round pass records the stage that ran it, not the stage the position now names"() {
        given:
        def identity = new TokenSequence([FIRST])
        executor.scripted << completed()
        builtinRunner.scripted << new Verdict.Pass(null, [])

        when:
        new Engine().run(pipeline(stage('build', 3, [builtin('files_exist')]), stage('review', 3, [])),
        CONTEXT, TaskState.atStageStart('build'), WORKSPACE, portsWith(identity))

        then: 'the round commit\'s state has advanced, and its round names build, task and token'
        def persisted = persistence.entries[0].state
        persisted.position() == new Position.AtStage('review')
        def round = persisted.attempts().last()
        round.stage() == 'build'
        round.taskId() == CONTEXT.taskId()
        round.round() == 0
        round.roundToken() == FIRST
    }

    // FR21: each recorded round takes its own token, once
    def "FR21: two recorded rounds carry the two tokens their rounds opened on, one take each"() {
        given:
        def identity = new TokenSequence([FIRST, SECOND])
        executor.scripted << completed(0) << completed(1)
        builtinRunner.scripted << fail('red') << new Verdict.Pass(null, [])

        when:
        new Engine().run(pipeline(stage('build', 3, [builtin('files_exist')])),
        CONTEXT, TaskState.atStageStart('build'), WORKSPACE, portsWith(identity))

        then:
        def rounds = persistence.entries.last().state.attempts()
        rounds*.roundToken() == [FIRST, SECOND]
        rounds*.round() == [0, 1]
        identity.taken == 2
    }

    // FR21: the in-place identity records a round with no token
    def "FR21: an in-place run records its rounds with no token"() {
        given:
        executor.scripted << completed()
        builtinRunner.scripted << new Verdict.Pass(null, [])

        when:
        new Engine().run(pipeline(stage('build', 3, [builtin('files_exist')])),
        CONTEXT, TaskState.atStageStart('build'), WORKSPACE, portsWith(RoundIdentity.inPlace()))

        then:
        def round = persistence.entries[0].state.attempts().last()
        round.roundToken() == null
        round.stage() == 'build'
    }

    // FR14: an executor-reported quality failure and a decision request are recorded rounds too
    def "FR14: a #shape round is stamped with its identity"() {
        given:
        def identity = new TokenSequence([FIRST])
        executor.scripted << result

        when:
        new Engine().run(pipeline(stage('build', 1, [builtin('files_exist')])),
        CONTEXT, TaskState.atStageStart('build'), WORKSPACE, portsWith(identity))

        then:
        def round = persistence.entries[0].state.attempts().last()
        round.roundToken() == FIRST
        round.stage() == 'build'
        round.taskId() == CONTEXT.taskId()

        where:
        shape | result
        'quality-failed' | new ExecutionResult(
                new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY, UntrustedText.agent('bad'), []),
                null, ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])
        'decision-needed' | ExecutionResult.decisionNeeded(UntrustedText.agent('which?'), [UntrustedText.agent('a')],
        ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])
    }

    // FR21: an unrecorded round (an executor throw) takes no token
    def "FR21: a round the engine does not record takes no token"() {
        given:
        def identity = new TokenSequence([])
        executor.toThrow = new RuntimeException('executor broke')

        when:
        new Engine().run(pipeline(stage('build', 3, [builtin('files_exist')])),
        CONTEXT, TaskState.atStageStart('build'), WORKSPACE, portsWith(identity))

        then:
        identity.taken == 0
        persistence.entries.empty
    }
}
