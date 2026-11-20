package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * FR10, NFR-R4 of migrate-verify-to-executor-contract (design D12 as amended 2026-11-12, D21): a
 * {@code poll} answering {@code pending} with another key rotates the check's entry — the new key,
 * the same check, attempt, token and {@code since} — and the whole list, every other entry carried
 * unchanged, goes through the one pending write before the next poll; a poll naming its key again
 * writes nothing; a rotation write that throws polls nothing more, and the record it failed to move
 * still names the previous key, which a resume polls with no {@code start}. On fake ports and
 * virtual time.
 */
class PendingPollRotationSpec extends PendingPollSpecBase {

    /** An executor answering {@code script} that notes how many pending writes preceded each request. */
    private ScriptedExecutor counting(List<ExecutionResult> script, List<Integer> writesBefore) {
        new ScriptedExecutor(script) {
                    @Override
                    ExecutionResult execute(StageExecutor.Request request) {
                        writesBefore << persistence.pending.size()
                        super.execute(request)
                    }
                }
    }

    // Scenario: verification/pending-poll / New key lands before the next poll
    def "FR10: a poll answering pending with a new key writes the rotated entry before the next poll, which names the new key"() {
        given:
        def writesBefore = []
        def executor = counting([
            pending('k-1'),
            pending('k-2'),
            passed()
        ], writesBefore)
        def ci = check()
        def since = clock.instant()

        when:
        def answer = poll(executor).answer(ci, ref(0, ci), round([ci])).answer()

        then: 'the second write came after the poll that returned k-2 and before the poll of k-2'
        answer == new VerdictAnswer.Sarif([], null)
        turns(executor) == [
            null,
            [ISSUED, 'k-1'],
            [ISSUED, 'k-2']
        ]
        writesBefore == [0, 1, 2]

        and: 'the rotated entry keeps its check, attempt, token and since: the timeout is not extended'
        persistence.pending*.state*.pendingPolls() == [
            [
                entry(ref(0, ci), 'k-1', since)
            ],
            [
                entry(ref(0, ci), 'k-2', since)
            ]
        ]
        persistence.pending*.state.every {
            it.attempts() == STATE.attempts() && it.position() == STATE.position()
        }
        sleeper.slept == [INTERVAL, INTERVAL]
    }

    def "FR10: a poll naming the recorded key again writes nothing"() {
        given:
        def executor = new ScriptedExecutor([
            pending('k-1'),
            pending('k-1'),
            passed()
        ])
        def ci = check()

        when:
        poll(executor).answer(ci, ref(0, ci), round([ci])).answer()

        then:
        turns(executor) == [
            null,
            [ISSUED, 'k-1'],
            [ISSUED, 'k-1']
        ]
        persistence.pending.size() == 1
    }

    def "FR10: a resumed entry rotated in its place carries every other entry of the round unchanged (#rotating)"() {
        given: 'the round recorded two pending checks; the one continued here rotates'
        def first = check('first')
        def second = check('second')
        def recorded = [
            entry(ref(0, first), 'k-a', clock.instant()),
            entry(ref(1, second), 'k-b', clock.instant().plusSeconds(60))
        ]
        def checks = [first, second]
        def executor = new ScriptedExecutor([pending('k-new'), passed()])

        when:
        poll(executor).answer(checks[index], ref(index, checks[index]), round(checks, STATE.withPendingPolls(recorded))).answer()

        then:
        turns(executor) == [
            [
                RECORDED,
                recorded[index].key().value()
            ],
            [ISSUED, 'k-new']
        ]
        def written = persistence.pending*.state*.pendingPolls()
        written.size() == 1
        written[0] == recorded.withIndex().collect { PendingPollRecord e, int i ->
            i == index ? new PendingPollRecord(e.check(), e.attempt(), e.token(), new PendingKey('k-new'), e.since()) : e
        }

        where:
        rotating | index
        'first' | 0
        'second' | 1
    }

    // Scenario: verification/pending-poll / Kill before the new key lands polls the previous key
    def "FR10, NFR-R4: a rotation write that fails polls nothing more, and a resume polls the previous key with no start"() {
        given: 'an executor that rotates the key; the write of the rotation throws, as a kill before it lands'
        def ci = check()
        def dying = new ScriptedExecutor([
            pending('k-1'),
            pending('k-2'),
            passed()
        ]) {
            @Override
            ExecutionResult execute(StageExecutor.Request request) {
                if (request.pending() != null) {
                    persistence.failPending = new IllegalStateException('killed before the rotated commit')
                }
                super.execute(request)
            }
        }

        when:
        def verified = orchestrator(poll(dying)).verify(VerifyingStages.of([ci]), CONTEXT, WORKSPACE, KEY, STATE, TOKEN)

        then: 'the new key is never polled, the check is cannot-verify, and the last write that landed names k-1'
        turns(dying) == [null, [ISSUED, 'k-1']]
        verified.results()*.verdict()*.getClass() == [Verdict.CannotVerify]
        persistence.pending*.state*.pendingPolls().collect {
            it*.key()*.value()
        } == [['k-1'], ['k-2']]

        when: 'a resume reads the record the kill left and the executor refuses the previous key'
        persistence.failPending = null
        def refusal = UntrustedText.factory("unknown pending key 'k-1'")
        def refusing = new ScriptedExecutor([
            result(new ExecutionResult.Failed(ExecutionResult.FailureClass.INFRASTRUCTURE, refusal, [], null))
        ])
        def answer = poll(refusing).answer(ci, ref(0, ci), round([ci], persistence.pending[0].state)).answer()

        then: 'the previous key is polled, nothing is started, and the infrastructure failure names the key'
        turns(refusing) == [[RECORDED, 'k-1']]
        answer == new VerdictAnswer.InfrastructureFailure(refusal, UntrustedText.factory(''))
    }
}
