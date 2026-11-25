package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration

/**
 * The resumed half of the one path of a pending {@code transform} round (FR11, NFR-R3, NFR-R4 of
 * extract-agent-round-runner; design D11, "Resume"; crash-consistency item 14): the engine reads the
 * round's entry off the registered state against the token of the pending round the run's cell was
 * restored with, and continues it through {@code PendingPoll.resumeRound} — a {@code RECORDED} poll, no
 * {@code start} — taking the round's token only when it does.
 */
class PendingRoundResumeSpec extends PendingTransformRoundSpecBase {

    static final RoundToken OTHER = RoundToken.of('b' * 40)

    /** How many requests the executor had received at each take of the round's token. */
    final List<Integer> requestsAtTake = []

    /** The run's identity: {@code restored} is the pending round its cell holds, or null for none. */
    private RoundIdentity identity(RoundToken restored) {
        [takeToken: {
                requestsAtTake << executor.requests.size()
                Optional.of(TOKEN)
            }, pendingToken: {
                Optional.ofNullable(restored)
            }] as RoundIdentity
    }

    private PendingPollRecord entry(RoundToken token, Duration age = Duration.ZERO, int attempt = 0) {
        new PendingPollRecord(PendingSubject.ROUND, attempt, token, new PendingKey('job-42'), clock.instant().minus(age))
    }

    private TaskOutcome resume(PendingPollRecord recorded, RoundToken restored) {
        def described = new DescribedExecutors(ExecutorVersions.none(), ExecutorCredentialNames.none(), ['remote'] as Set)
        def ports = new EnginePorts(CheckRouting.of(executor, checks, new ScriptedCheckRounds(), new ScriptedCheckRounds()),
                listener, persistence, VirtualTimeEquipment.on(clock, sleeper), AttemptDelivery.assumedDelivered(),
                described, identity(restored), BOUNDS)
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [STAGE]), CONTEXT,
        TaskState.atStageStart('build').withPendingPolls([recorded]), new FakeWorkspace(), ports)
    }

    def "FR11: a pending round the run's cell was restored with continues its recorded key with no start"() {
        given:
        executor.scripted << completed()
        checks.scripted << new Verdict.Pass(null, [])

        when:
        def outcome = resume(entry(TOKEN), TOKEN)

        then: 'one request, a RECORDED poll of the recorded key; no start'
        executor.turns == [
            new PendingTurn(new PendingKey('job-42'), PendingTurn.Origin.RECORDED)
        ]

        and: 'the round closed on the poll\'s result and was verified once, after the close'
        outcome instanceof TaskOutcome.Completed
        checks.calls.size() == 1
        persistence.entries.size() == 1

        and: 'its record carries the restored token, taken once before the poll, and no pending entry'
        requestsAtTake == [0]
        def record = persistence.entries[0].state.attempts().last()
        record.roundToken() == TOKEN
        persistence.entries[0].state.pendingPolls().isEmpty()

        and: 'the round\'s finish names the key it was resumed with (UX3), never a pending key of a start'
        def finished = listener.events.find {
            it instanceof EngineEvent.ExecutionFinished
        } as EngineEvent.ExecutionFinished
        finished.resumedKey() == UntrustedText.subprocess('job-42')
        finished.pendingKey() == null
    }

    // FR10, FR11 (D11; crash-consistency item 12): a pending round's record authorises no verify chain
    //     and no advancement; what a pickup does is poll the recorded key
    // Scenario: pipeline/stage-engine / A pending round is never verified
    def "FR11: a resumed pending round still pending starts no verify check and keeps its position until the ceiling"() {
        given: 'an entry recorded 59 minutes ago; the executor still answers pending with the recorded key'
        executor.scripted << pending('job-42') << pending('job-42')

        when:
        def outcome = resume(entry(TOKEN, Duration.ofMinutes(59)), TOKEN)

        then: 'only polls of the recorded key were made'
        executor.turns*.key() == [new PendingKey('job-42')] * 2
        executor.turns.every { it != null }

        and: 'no verify check started, nothing was recorded, the position is unchanged'
        checks.calls.isEmpty()
        persistence.entries.isEmpty()
        persistence.pending.isEmpty()
        outcome instanceof TaskOutcome.Escalated
        outcome.finalState().position() == TaskState.atStageStart('build').position()
    }

    // FR11, NFR-R3 (item 13: identity, never presence): an entry is continued only when it names the
    //     restored pending round; otherwise the round starts, and its token is taken only after the start
    def "FR11: an entry the run does not continue — #variant — starts the round, and the token is taken after the start"() {
        given:
        executor.scripted << completed()
        checks.scripted << new Verdict.Pass(null, [])

        when:
        resume(entry(recorded, Duration.ZERO, attempt), restored)

        then:
        executor.turns == [null]
        requestsAtTake == [1]

        where:
        variant | recorded | attempt | restored
        'no pending round restored' | TOKEN | 0 | null
        'an entry of another round\'s token' | OTHER | 0 | TOKEN
        'an entry of another attempt' | TOKEN | 1 | TOKEN
    }
}
