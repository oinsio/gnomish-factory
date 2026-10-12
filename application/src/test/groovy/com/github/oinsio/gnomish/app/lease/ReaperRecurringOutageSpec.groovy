package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR9 of add-claim-heartbeat, the accepted cost of "an observer that cannot read versions observes
 * nothing": every failed {@code listOpen} forgets the observation windows, so a listing that fails
 * more often than once per TTL keeps restarting a dead claim's window and delays its reaping for as
 * long as the pattern holds. The design prefers that delay to any reap of a live claim. Driven
 * through {@code reapOnce} on virtual monotonic time, with a scripted listing.
 */
class ReaperRecurringOutageSpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final Duration TICK = Duration.ofMinutes(5)

    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL)

    private static final ClaimVersion DEAD = version('dead', '2000-01-01T00:00:00Z')

    // FR9: a recurring listing failure costs a delayed reap of a dead claim, never a reap of a live one
    // Scenario: lifecycle/claim-heartbeat / A recurring listing failure may delay reaping
    def "FR9: a listing that fails every second poll delays a dead claim's reap until the pattern stops"() {
        given: 'a dead claim that never changes and a live claim beaten on every tick'
        int beats = 0
        int polls = 0
        boolean failing = true
        List<TaskRef> removed = []
        def tracker = Stub(Tracker) {
            listReady(_) >> []
            listOpen() >> {
                polls++
                if (failing && polls % 2 == 0) {
                    throw new RuntimeException('tracker flapping')
                }
                beats++
                [
                    working('T-dead', DEAD),
                    working('T-live', version('live', Instant.parse('2026-08-01T10:00:00Z').plusSeconds(beats).toString()))
                ]
            }
            removeStaleClaim(_, _) >> { TaskRef ref, ClaimFacts claim ->
                removed << ref
                new RemoveStaleClaimResult.Removed()
            }
        }
        def reaper = new Reaper(tracker, memory)

        when: 'the pattern holds across many TTLs'
        (1..24).each {
            reaper.reapOnce([])
            time.advance(TICK)
        }

        then: 'no window ever accrued a full TTL: nothing was reaped, the live claim least of all'
        removed.isEmpty()

        when: 'the listing stops failing'
        failing = false
        (1..5).each {
            reaper.reapOnce([])
            time.advance(TICK)
        }

        then: 'the dead claim is reaped once its window spans a TTL; the live one never'
        removed == [new TaskRef('T-dead')]
    }

    private static OpenTask working(String ref, ClaimVersion version) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('inst-1'), version, UntrustedText.tracker('title'))
    }

    private static ClaimVersion version(String marker, String updatedAt) {
        new ClaimVersion(marker, Instant.parse(updatedAt), new ClaimEpoch(1))
    }
}
