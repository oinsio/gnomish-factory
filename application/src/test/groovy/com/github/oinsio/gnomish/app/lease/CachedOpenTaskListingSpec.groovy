package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant
import spock.lang.Specification

/**
 * CachedOpenTaskListing: the real {@link OpenTaskListingSink} the reaper publishes into and
 * {@link LivenessOracle} reads from. Fail-closed by construction (starts Failed) and never
 * conflates a fresh outage with a stale-but-still-cached success.
 *
 * FR3, NFR-C2, NFR-R1 of add-serve-sandbox-lifecycle.
 */
class CachedOpenTaskListingSpec extends Specification {

    private final CachedOpenTaskListing cache = new CachedOpenTaskListing()

    private static OpenTask working(String ref) {
        new OpenTask(new TaskRef(ref), new TrackerTaskState.Working('inst-1'),
                new ClaimVersion('m1', Instant.parse('2000-01-01T00:00:00Z')), 'fixture title')
    }

    def "starts Failed before any tick has published"() {
        expect:
        cache.current() instanceof CachedOpenTaskListing.Listing.Failed
    }

    def "onListed publishes an Observed listing"() {
        given:
        def open = [working('T-1')]

        when:
        cache.onListed(open)

        then:
        def listing = cache.current()
        listing instanceof CachedOpenTaskListing.Listing.Observed
        ((CachedOpenTaskListing.Listing.Observed) listing).openTasks() == open
    }

    def "onListingFailed replaces a prior Observed listing with Failed"() {
        given:
        cache.onListed([working('T-1')])

        when:
        cache.onListingFailed()

        then:
        cache.current() instanceof CachedOpenTaskListing.Listing.Failed
    }
}
