package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * The engine path of a pending {@code transform} round (FR10, FR11, NFR-O2, UX3 of
 * extract-agent-round-runner; design D11): the round's {@code pending} is handed to {@link PendingPoll},
 * the pending round commit is written before the first poll and moves nothing, and the terminal answer
 * closes the round through the normal close with no pending record left, its record carrying the usage
 * of every request under the one token.
 */
class PendingTransformRoundSpec extends PendingTransformRoundSpecBase {

    // FR10, FR11: the pending round commit — the round's entry on the last persisted state, nothing
    //     else changed — lands before the first poll, which polls the issued key with no check
    // Scenario: pipeline/stage-engine / Pending round commit lands before the first poll
    // Scenario: executor/wire-contract / Pending from a transform executor that can be polled
    def "a pending start writes the round's entry before the first poll, which polls the issued key"() {
        given:
        executor.scripted << pending('job-42') << completed()
        checks.scripted << new Verdict.Pass(null, [])
        def startedAt = clock.instant()

        when:
        run()

        then: 'one pending write, made before the poll: the round entry, the position and history untouched'
        persistence.pending.size() == 1
        persistence.pending[0].taskId == 'TASK-1'
        persistence.pending[0].state == TaskState.atStageStart('build').withPendingPolls([
            new PendingPollRecord(PendingSubject.ROUND, 0, TOKEN, new PendingKey('job-42'), startedAt)
        ])
        executor.writesBefore == [0, 1]

        and: 'the poll is the round\'s own request: no check, the issued key'
        def poll = executor.requests[1]
        poll.check() == null
        poll.pending() == new PendingTurn(new PendingKey('job-42'), PendingTurn.Origin.ISSUED)
        poll.stage() == STAGE
        poll.attempt() == 0
    }

    // FR10, item 12 of crash-consistency: no verify check starts while the round is pending, and the
    //     round's close is the only write that records an attempt
    def "no check runs and no attempt is recorded while the round is pending"() {
        given:
        executor.scripted << pending('job-42') << pending('job-42') << completed()
        checks.scripted << new Verdict.Pass(null, [])

        when:
        def outcome = run()

        then: 'the round recorded once, after its terminal poll; one check ran, after the last poll'
        outcome instanceof TaskOutcome.Completed
        persistence.entries.size() == 1
        checks.calls.size() == 1
        def kinds = listener.events*.class
        kinds.lastIndexOf(EngineEvent.PendingPolled) <kinds.indexOf(EngineEvent.CheckStarted)
        persistence.pending*.state*.attempts().every { it.isEmpty() }
    }

    // FR10: the terminal poll closes the round through the normal close, whose state drops the entry;
    //     the round's record carries the token it went pending with, taken once
    def "the closing commit drops the pending record and carries the round's one token"() {
        given:
        executor.scripted << pending('job-42') << completed()
        checks.scripted << new Verdict.Pass(null, [])

        when:
        def outcome = run()

        then:
        persistence.entries.size() == 1
        persistence.entries[0].state.pendingPolls().isEmpty()
        persistence.entries[0].state.attempts()[0].roundToken() == TOKEN
        taken == 1
    }

    // NFR-O2: the usage of the start and of every poll is recorded under the one round
    def "the round records one transform entry per request, start first"() {
        given:
        executor.scripted << pending('job-42', null, 10) << pending('job-42', null, 20) << completed(30)
        checks.scripted << new Verdict.Pass(null, [])

        when:
        def outcome = run()

        then:
        def usage = persistence.entries[0].state.attempts()[0].usage()
        usage*.role() == [ExecutorRole.TRANSFORM] * 3
        usage*.executor() == ['remote'] * 3
        usage.collect { it.byModel()['m-1'].tokens().input() } == [10, 20, 30]
    }
}
