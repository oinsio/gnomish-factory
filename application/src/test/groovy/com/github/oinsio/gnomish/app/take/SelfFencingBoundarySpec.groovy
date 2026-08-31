package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import spock.lang.Specification

/**
 * FR13 of harden-task-branch-contract, claim-heartbeat "Unconfirmed heartbeat freezes writes at the
 * boundary": a holder that can no longer confirm its claim writes nothing at its next round boundary
 * until one re-verification says the claim is still ours. Unlike the revocation check, this gate runs
 * BEFORE the delegate persists — the whole point is that the round is not written.
 */
class SelfFencingBoundarySpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final TaskState STATE = TaskState.atStageStart('implement')
    private static final ToolTrace TRACE = new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0), [])

    private AttemptPersistence delegate = Mock()
    private Tracker tracker = Mock()
    private ClaimLossFlag flag = new ClaimLossFlag()
    private RevocationCheckingAttemptPersistence persistence =
    new RevocationCheckingAttemptPersistence(delegate, tracker, REF, INSTANCE, flag)

    private static TrackerTask taskWith(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'), state, AbortFacts.none(), false)
    }

    // FR13: an unfenced boundary costs no extra read — the freeze is the exception, not the rule
    def "a confirmed claim makes no re-verification read of its own"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then: 'exactly one fetchTask — the ordinary post-persist revocation check, no second read'
        1 * delegate.persist('PROJ-1', STATE, TRACE)
        1 * tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
    }

    // FR13: connectivity returned and the claim is still ours — the freeze lifts and the round is
    //     written normally, so an outage that ends costs the run nothing
    def "a re-verified claim lifts the freeze and the round is written"() {
        given:
        flag.claimUnconfirmed(REF)
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        1 * delegate.persist('PROJ-1', STATE, TRACE)
        noExceptionThrown()

        and: 'the claim is confirmed again, so the next boundary is not frozen'
        !flag.isUnconfirmed(REF)
    }

    // FR13: the write the fence exists to prevent — a superseded holder must not commit or push a
    //     round for a task another instance now holds
    def "a claim that moved freezes the round: nothing is written"() {
        given:
        flag.claimUnconfirmed(REF)
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working('gnomish-other-99'))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then: 'the delegate never runs — no round commit, no push'
        0 * delegate.persist(_, _, _)
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('PROJ-1')

        and: 'the run learns of it exactly as it learns of a revocation'
        persistence.revocation().get() == ex
    }

    // FR13: a task a human returned to Ready is not ours either — every non-ours answer freezes
    def "a task no longer Working freezes the round too"() {
        given:
        flag.claimUnconfirmed(REF)
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Ready())

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        0 * delegate.persist(_, _, _)
        thrown(RevocationDetectedException)
    }

    // FR8: a claim already proved gone takes the lost path, and it does so without writing the
    //     round — the lost check runs after the delegate, so the freeze is what stops the write here
    def "a claim both unconfirmed and gone writes nothing"() {
        given:
        flag.claimUnconfirmed(REF)
        flag.claimLost(REF)
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Ready())

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        0 * delegate.persist(_, _, _)
        thrown(RevocationDetectedException)
    }
}
