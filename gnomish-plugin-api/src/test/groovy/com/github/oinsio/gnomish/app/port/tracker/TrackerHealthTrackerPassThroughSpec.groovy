package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Instant
import spock.lang.Specification

/**
 * FR8, D12 of add-serve-observability: {@link TrackerHealthTracker} is a <em>transparent</em>
 * decorator — it observes calls, it never alters them. {@link TrackerHealthTrackerSpec} pins the
 * observation half (lastSuccessAt / consecutiveFailures); this spec pins the transparency half,
 * that every value-returning port method hands back the delegate's own result untouched.
 *
 * <p>FR11, design D6 of split-into-modules: the decorator's pass-through used to be proven only
 * indirectly, by the daemon and adapter specs that ran a real tracker through it. Those specs live
 * in other modules now, so the module that owns the class owns the proof (NFR-P1) — otherwise a
 * "return null instead of the delegate's result" defect would survive `:gnomish-plugin-api:check`.
 */
class TrackerHealthTrackerPassThroughSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    private Tracker delegate = Mock()
    private TrackerHealthTracker tracker = new TrackerHealthTracker(delegate, new VirtualClock())

    def "listReady returns the delegate's list unchanged"() {
        given:
        def ready = [
            new ReadyTask(REF, AbortFacts.none(), false, false, 'a title')
        ]
        delegate.listReady(7) >> ready

        expect:
        tracker.listReady(7) == ready
    }

    def "claim returns the delegate's result unchanged"() {
        given:
        def result = new ClaimResult.Held('other-instance')
        delegate.claim(REF, 'me') >> result

        expect:
        tracker.claim(REF, 'me').is(result)
    }

    def "collectDecisions returns the delegate's list unchanged"() {
        given:
        def replies = [
            new HumanReply('do it', Instant.EPOCH)
        ]
        delegate.collectDecisions(REF) >> replies

        expect:
        tracker.collectDecisions(REF) == replies
    }

    def "listOpen returns the delegate's list unchanged"() {
        given:
        def openTasks = [
            new OpenTask(REF, new TrackerTaskState.Gone(null), null, 'a title')
        ]
        delegate.listOpen() >> openTasks

        expect:
        tracker.listOpen() == openTasks
    }
}
