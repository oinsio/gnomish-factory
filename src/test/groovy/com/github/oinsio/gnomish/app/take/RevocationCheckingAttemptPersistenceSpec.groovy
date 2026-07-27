package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
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
 * FR15, D2 of add-tracker-port: the round-boundary revocation check that decorates the engine's
 * AttemptPersistence port. The delegate's persist always runs first (the round is durable before
 * revocation can even be checked); then one fetchTask decides "still ours and alive" — Working
 * held by this instance is the only pass, everything else throws RevocationDetectedException.
 */
class RevocationCheckingAttemptPersistenceSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final TaskState STATE = TaskState.atStageStart('implement')
    private static final ToolTrace TRACE = new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0), [])

    private AttemptPersistence delegate = Mock()
    private Tracker tracker = Mock()
    private RevocationCheckingAttemptPersistence persistence =
    new RevocationCheckingAttemptPersistence(delegate, tracker, REF, INSTANCE)

    private TrackerTask taskWith(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'), state, AbortFacts.none())
    }

    def "persist delegates first, then passes when the task is still Working held by this instance"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        1 * delegate.persist('PROJ-1', STATE, TRACE)
        noExceptionThrown()

        and: 'revocation() stays empty — no revocation was ever detected'
        persistence.revocation().isEmpty()
    }

    def "persist throws RevocationDetectedException when the task is Gone"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Gone())

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        1 * delegate.persist('PROJ-1', STATE, TRACE)
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('PROJ-1')
        ex.message.contains('task closed or nonexistent')

        and: 'the same exception is recorded on the instance for a caller to query after engine.run(...) returns'
        persistence.revocation().isPresent()
        persistence.revocation().get() == ex
    }

    def "a Gone closure reason is folded into the revocation context"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Gone('completed'))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        1 * delegate.persist('PROJ-1', STATE, TRACE)
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('task closed or nonexistent (completed)')
    }

    def "persist throws RevocationDetectedException when the claim is held by another instance"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working('other-instance-xyz'))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        1 * delegate.persist('PROJ-1', STATE, TRACE)
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('claim held by another instance (other-instance-xyz)')
    }

    def "persist throws RevocationDetectedException when the task was parked by a human"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION))

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('task parked awaiting human (ESCALATION)')
    }

    def "persist throws RevocationDetectedException when the task was released back to Ready"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Ready())

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('task released back to ready')
    }

    def "persist throws RevocationDetectedException when the task is already Finished"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Finished())

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then:
        def ex = thrown(RevocationDetectedException)
        ex.message.contains('task already finished')
    }

    def "the revocation check runs only after the delegate persist completes"() {
        given: 'the delegate throws — the round itself failed to persist durably'
        delegate.persist(*_) >> { throw new RuntimeException('disk full') }

        when:
        persistence.persist('PROJ-1', STATE, TRACE)

        then: 'the delegate failure propagates unchanged and fetchTask is never reached'
        def ex = thrown(RuntimeException)
        ex.message == 'disk full'
        0 * tracker.fetchTask(*_)
    }
}
