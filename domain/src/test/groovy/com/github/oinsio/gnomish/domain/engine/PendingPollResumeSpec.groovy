package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.pendingkey.PendingKey
import java.time.Duration

/**
 * FR3, FR20, NFR-R1, NFR-R4 of migrate-verify-to-executor-contract (design D21, crash-consistency
 * items 13 and 14): an entry of the state a round verifies is continued — a {@code RECORDED} poll of
 * its key, no {@code start} — only when its attempt is the round's and its token equals the round's
 * token; every such entry of the round is continued, an ended one included; the continuation is the
 * live path's own wait, its deadline counted from the entry's {@code since}. On fake ports; the real
 * medium re-asserts the markers in task 4.3.
 */
class PendingPollResumeSpec extends PendingPollSpecBase {

    // Scenario: verification/pending-poll / Every pending check of the round is continued
    def "FR3, NFR-R4: both recorded keys of the round are polled, the ended one included, and neither check starts"() {
        given: 'the first check ended and the second was being polled when the factory was killed'
        def first = check('first')
        def second = check('second')
        def state = STATE.withPendingPolls([
            entry(ref(0, first), 'k-a', clock.instant()),
            entry(ref(1, second), 'k-b', clock.instant())
        ])
        def executor = new ScriptedExecutor([passed(), passed()])

        when:
        def result = orchestrator(poll(executor)).verify(VerifyingStages.of([first, second]), CONTEXT, WORKSPACE, KEY,
        state, TOKEN)

        then:
        turns(executor) == [
            [RECORDED, 'k-a'],
            [RECORDED, 'k-b']
        ]
        result.results()*.verdict()*.getClass() == [Verdict.Pass, Verdict.Pass]

        and: 'nothing is written again'
        persistence.pending.isEmpty()
    }

    // Scenario: verification/pending-poll / An entry of another round is not continued
    def "FR20: an entry #why is not polled; the check starts, and its write drops the stale entry"() {
        given:
        def ci = check()
        def stale = entry(ref(0, ci), 'k-old', clock.instant(), entryToken, entryAttempt)
        def executor = new ScriptedExecutor([pending('k-new'), passed()])

        when:
        def answer = poll(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci], STATE.withPendingPolls([stale]), roundToken)).answer()

        then:
        answer == new VerdictAnswer.Sarif([], null)
        turns(executor) == [null, [ISSUED, 'k-new']]
        persistence.pending[0].state.pendingPolls()*.key()*.value() == ['k-new']

        where:
        why | entryToken | entryAttempt | roundToken
        'of another round token' | OTHER_TOKEN | 0 | TOKEN
        'of another attempt' | TOKEN | 1 | TOKEN
        'with no token' | null | 0 | TOKEN
        'when the round has no token (in-place)' | null | 0 | null
    }

    // D11 of extract-agent-round-runner: the round's own entry is the round's, never a check's — it
    //     is not polled for a check, and a check's pending write does not carry it forward
    def "FR10 of extract-agent-round-runner: a round entry of the round is not continued by a check and not written again"() {
        given:
        def ci = check()
        def roundEntry = new PendingPollRecord(PendingSubject.ROUND, 0, TOKEN, new PendingKey('k-round'), clock.instant())
        def executor = new ScriptedExecutor([pending('k-new'), passed()])

        when:
        poll(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci], STATE.withPendingPolls([roundEntry]))).answer()

        then:
        turns(executor) == [null, [ISSUED, 'k-new']]
        persistence.pending[0].state.pendingPolls()*.subject() == [
            new PendingSubject.Check(ref(0, ci))
        ]
    }

    def "FR3: an entry of another check of the round is not this check's"() {
        given:
        def ci = check('ci')
        def other = check('other')
        def executor = new ScriptedExecutor([passed()])
        def state = STATE.withPendingPolls([
            entry(ref(1, other), 'k-other', clock.instant())
        ])

        when:
        poll(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci, other], state)).answer()

        then:
        turns(executor) == [null]
    }

    // FR3, D14 as amended 2026-11-13: the subject the run's table of pending runs is keyed by travels on every request
    def "FR3: every request of a check's rounds — its start, its issued poll, a recorded poll — carries that check's CheckRef"() {
        given: 'the first check starts; the second has an entry of the round'
        def first = check('first')
        def second = check('second')
        def state = STATE.withPendingPolls([
            entry(ref(1, second), 'k-b', clock.instant())
        ])
        def executor = new ScriptedExecutor([
            pending('k-a'),
            passed(),
            passed()
        ])

        when:
        orchestrator(poll(executor)).verify(VerifyingStages.of([first, second]), CONTEXT, WORKSPACE, KEY, state, TOKEN)

        then:
        [
            executor.requests,
            executor.turns
        ].transpose().collect { request, turn ->
            [
                request.check(),
                request.checkRef(),
                turn?.origin()
            ]
        } == [
            [first, ref(0, first), null],
            [first, ref(0, first), ISSUED],
            [
                second,
                ref(1, second),
                RECORDED
            ]
        ]
    }

    def "FR3: a resumed entry is polled at once, and a key a poll returns is polled as issued"() {
        given:
        def ci = check()
        def state = STATE.withPendingPolls([
            entry(ref(0, ci), 'k-1', clock.instant())
        ])
        def executor = new ScriptedExecutor([pending('k-2'), passed()])

        when:
        def answer = poll(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci], state)).answer()

        then:
        answer == new VerdictAnswer.Sarif([], null)
        turns(executor) == [
            [RECORDED, 'k-1'],
            [ISSUED, 'k-2']
        ]
        sleeper.slept == [INTERVAL]
    }

    def "NFR-R1: the resumed wait's deadline counts from the entry's since, not from the resume"() {
        given: 'an entry whose timeout ran out while no instance held the task'
        def ci = check()
        def state = STATE.withPendingPolls([
            entry(ref(0, ci), 'k-1', clock.instant())
        ])
        clock.advance(TIMEOUT.plus(Duration.ofMinutes(1)))
        def executor = new ScriptedExecutor([
            pending('k-1', null, PendingState.RUNNING, "waiting on 'ci' (running)")
        ])

        when:
        def answer = poll(executor).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci], state)).answer()

        then: 'one poll at once, then the timeout: no wait'
        turns(executor) == [[RECORDED, 'k-1']]
        sleeper.slept.isEmpty()
        answer instanceof VerdictAnswer.QualityFailure
    }

    def "FR20: a later round's entry carries its own attempt, and that round continues it"() {
        given:
        def ci = check()
        def secondRound = new AttemptKey('TASK-1', 'build', 2)
        def started = new ScriptedExecutor([pending('k-1'), passed()])
        def resumed = new ScriptedExecutor([passed()])

        when:
        poll(started).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci], STATE, TOKEN, secondRound)).answer()
        def written = persistence.pending[0].state
        poll(resumed).answer(ci, DescribedExecutors.none().executorOf(ci), ref(0, ci), round([ci], written, TOKEN, secondRound)).answer()

        then:
        written.pendingPolls()*.attempt() == [2]
        turns(resumed) == [[RECORDED, 'k-1']]
    }
}
