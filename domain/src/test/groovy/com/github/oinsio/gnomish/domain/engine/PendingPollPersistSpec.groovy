package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * FR2, FR20, NFR-R1 of migrate-verify-to-executor-contract (design D2, D21): a {@code start} that
 * answers {@code pending} adds the check's entry to the open round's list and writes the whole list
 * through {@code persistPending} before the first poll; a write that throws makes no poll, and the
 * verify chain resolves the check as cannot-verify; a start that ends is never written; a polled
 * check is asked only behind the delivery precondition. On fake ports and virtual time.
 */
class PendingPollPersistSpec extends PendingPollSpecBase {

    // Scenario: verification/pending-poll / Key lands before polling
    def "FR2: the key lands through persistPending before the first poll, on a state naming no outcome"() {
        given: 'an executor that notes how many pending writes preceded each request'
        def writesBefore = []
        def executor = new ScriptedExecutor([pending('k-1'), passed()]) {
            @Override
            protected ExecutionResult respond(StageExecutor.Request request, PendingTurn turn) {
                writesBefore << persistence.pending.size()
                super.respond(request, turn)
            }
        }
        def ci = check()
        def since = clock.instant()

        when:
        def answer = poll(executor).answer(ci, ref(0, ci), round([ci])).answer()

        then: 'the start went first, the poll of its key after exactly one pending write'
        turns(executor) == [null, [ISSUED, 'k-1']]
        writesBefore == [0, 1]
        answer == new VerdictAnswer.Sarif([], null)

        and: 'the write is the last persisted state with the entry, and no trace'
        persistence.pending*.taskId == ['TASK-1']
        persistence.pending[0].state == STATE.withPendingPolls([
            entry(ref(0, ci), 'k-1', since)
        ])
        persistence.pending[0].trace == null
        persistence.entries.isEmpty()

        and: 'each check round names the check, the stage and the round'
        executor.requests.every {
            it.check() == ci && it.stage().name() == 'build' && it.attempt() == 0
        }
    }

    def "FR2: a start that ends answers at once, with no pending write and no poll"() {
        given:
        def executor = new ScriptedExecutor([passed()])
        def ci = check()

        when:
        def answer = poll(executor).answer(ci, ref(0, ci), round([ci])).answer()

        then:
        answer == new VerdictAnswer.Sarif([], null)
        turns(executor) == [null]
        persistence.pending.isEmpty()
    }

    // D21, "What a failed persistPending comes to": no poll, and the check cannot-verify.
    def "FR2, NFR-R1: a throwing pending write makes no poll, and the verify chain resolves the check as cannot-verify"() {
        given:
        persistence.failPending = new IllegalStateException('pending commit refused')
        def executor = new ScriptedExecutor([pending('k-1'), passed()])
        def ci = check()

        when:
        def result = orchestrator(poll(executor)).verify(VerifyingStages.of([ci]), CONTEXT, WORKSPACE, KEY, STATE, TOKEN)

        then:
        turns(executor) == [null]
        persistence.pending.size() == 1
        result.results()*.verdict()*.getClass() == [Verdict.CannotVerify]
        (result.results()[0].verdict() as Verdict.CannotVerify).reason() == UntrustedText.factory('check adapter threw')
    }

    def "FR2: every pending check of a round adds its entry, the ended ones kept, so the second write carries both keys"() {
        given:
        def first = check('first')
        def second = check('second')
        def executor = new ScriptedExecutor([
            pending('k-a'),
            passed(),
            pending('k-b'),
            passed()
        ])
        def startedAt = clock.instant()

        when:
        def result = orchestrator(poll(executor)).verify(VerifyingStages.of([first, second]), CONTEXT, WORKSPACE, KEY,
        STATE, TOKEN)

        then:
        result.results()*.verdict()*.getClass() == [Verdict.Pass, Verdict.Pass]
        turns(executor) == [
            null,
            [ISSUED, 'k-a'],
            null,
            [ISSUED, 'k-b']
        ]
        persistence.pending*.state*.pendingPolls() == [
            [
                entry(ref(0, first), 'k-a', startedAt)
            ],
            [
                entry(ref(0, first), 'k-a', startedAt),
                entry(ref(1, second), 'k-b', startedAt.plus(INTERVAL))
            ]
        ]
    }

    // Scenario: git/git-task-persistence / An undeliverable attempt commit fails an external check as infrastructure
    def "FR21 of add-sandbox-core: a polled check behind an undeliverable commit is cannot-verify, nothing asked"() {
        given:
        def executor = new ScriptedExecutor([pending('k-1')])
        def asked = []
        def undeliverable = { workspace ->
            asked << workspace
            new AttemptDelivery.Outcome.Undeliverable(UntrustedText.factory('push refused'), UntrustedText.subprocess('denied'))
        } as AttemptDelivery
        def ci = check()

        when:
        def answer = poll(executor, undeliverable).answer(ci, ref(0, ci), round([ci])).answer()

        then:
        answer == new VerdictAnswer.InfrastructureFailure(UntrustedText.factory('push refused'), UntrustedText.subprocess('denied'))
        executor.requests.isEmpty()
        persistence.pending.isEmpty()

        and: 'the precondition was asked of the round working copy'
        asked.size() == 1
        asked[0].is(WORKSPACE)
    }

    def "a check that is not polled is asked without the delivery precondition"() {
        given:
        def delivery = Mock(AttemptDelivery)
        def executor = new ScriptedExecutor([passed()])
        VerifyCheck shell = VerifyChecks.command('./gradlew test')

        when:
        def answer = poll(executor, delivery).answer(shell, ref(0, shell), round([shell])).answer()

        then:
        answer == new VerdictAnswer.Sarif([], null)
        0 * delivery._
        turns(executor) == [null]
    }

    def "the entry's since is the instant the first pending answer was taken"() {
        given:
        clock.advance(java.time.Duration.ofMinutes(3))
        def executor = new ScriptedExecutor([pending('k-1'), passed()])
        def ci = check()

        when:
        poll(executor).answer(ci, ref(0, ci), round([ci])).answer()

        then:
        persistence.pending[0].state.pendingPolls()[0].since() == Instant.EPOCH.plus(java.time.Duration.ofMinutes(3))
    }
}
