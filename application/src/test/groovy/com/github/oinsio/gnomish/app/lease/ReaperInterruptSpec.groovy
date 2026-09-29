package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link Reaper} under a stop (FR12, NFR-O2 of fix-operator-blockers): a tracker call that fails on
 * a thread whose interrupt is set failed because the standing reaper was told to stop. The sweep
 * returns quietly — no WARN, no listing-failed signal, and the observation windows are kept, since
 * nothing about the tracker was learned — and it makes no further tracker call. A failure on an
 * uninterrupted thread is the outage it always was (FR9 of add-claim-heartbeat).
 *
 * <p>Whether the windows survived is read from their effect: a claim first seen a full TTL before
 * the failed tick is reaped on the next successful tick only if its window was kept.
 *
 * Implements FR12, NFR-O2 of fix-operator-blockers.
 */
class ReaperInterruptSpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final ClaimVersion VERSION =
    new ClaimVersion('m1', Instant.parse('2000-01-01T00:00:00Z'), new ClaimEpoch(1))

    private final Tracker tracker = Mock(Tracker) { listReady(_) >> [] }
    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final OpenTaskListingSink sink = Mock()
    private final Reaper reaper = new Reaper(tracker, new StalenessMemory(time, TTL), sink)
    private final LogCaptureSupport logs = LogCaptureSupport.attach(Reaper)

    def cleanup() {
        logs.detach()
        Thread.interrupted()
    }

    private static OpenTask working(String ref) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('inst-1'), VERSION, UntrustedText.tracker('fixture title'))
    }

    private boolean anyWarning() {
        logs.list.any { it.level.isGreaterOrEqual(Level.WARN) }
    }

    // FR12, NFR-O2: the stop is not an outage — quiet, no signal, windows kept, interrupt kept.
    def "a sweep listing that fails on an interrupted thread returns quietly and keeps the windows"() {
        given: 'a claim first seen a full TTL ago'
        tracker.listOpen() >> [working('T-1')]
        reaper.reapOnce([])
        time.advance(TTL)

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> {
            Thread.currentThread().interrupt()
            throw new RuntimeException('call cancelled')
        }
        0 * sink.onListingFailed()
        !anyWarning()
        Thread.currentThread().isInterrupted()

        when: 'the next tick lists the claim unchanged'
        Thread.interrupted()
        reaper.reapOnce([])

        then: 'the kept window has run its TTL, so the claim is reaped at once'
        1 * tracker.listOpen() >> [working('T-1')]
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), new ClaimFacts.Live('inst-1', VERSION)) >>
                new RemoveStaleClaimResult.Removed()
    }

    // FR12, NFR-O2, control row: the same failure with no interrupt is the outage of FR9.
    def "a sweep listing that fails on an uninterrupted thread warns and forgets the windows"() {
        given:
        tracker.listOpen() >> [working('T-1')]
        reaper.reapOnce([])
        time.advance(TTL)

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> { throw new RuntimeException('tracker down') }
        1 * sink.onListingFailed()
        logs.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.REAPER_SWEEP_LISTING_FAILED.head())
        }

        when:
        reaper.reapOnce([])

        then: 'the forgotten window restarts its TTL, so nothing is reaped yet'
        1 * tracker.listOpen() >> [working('T-1')]
        0 * tracker.removeStaleClaim(_, _)
    }

    // FR12: a repair interrupted mid-call ends the sweep — no repair-failed WARN, and no repair of
    //     the next released task is attempted on a thread that was told to stop.
    def "a repair that fails on an interrupted thread ends the sweep with no warning"() {
        given: 'two claims first seen a full TTL ago'
        tracker.listOpen() >> [
            working('T-1'),
            working('T-2')
        ]
        reaper.reapOnce([])
        time.advance(TTL)

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.removeStaleClaim(_, _) >> {
            Thread.currentThread().interrupt()
            throw new RuntimeException('call cancelled')
        }
        !anyWarning()
        Thread.currentThread().isInterrupted()
    }

    // FR12, control row: a repair failure nobody's stop caused is warned about and the rest run.
    def "a repair that fails on an uninterrupted thread warns and the rest still run"() {
        given:
        tracker.listOpen() >> [
            working('T-1'),
            working('T-2')
        ]
        reaper.reapOnce([])
        time.advance(TTL)

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.removeStaleClaim(new TaskRef('T-1'), _) >> {
            throw new RuntimeException('5xx')
        }
        1 * tracker.removeStaleClaim(new TaskRef('T-2'), _) >> new RemoveStaleClaimResult.Removed()
        logs.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.REAPER_REPAIR_FAILED.head())
        }
    }
}
