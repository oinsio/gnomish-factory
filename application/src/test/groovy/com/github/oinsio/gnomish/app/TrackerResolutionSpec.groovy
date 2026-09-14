package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * {@link TrackerResolution} (task 5.13, split from {@link TakeCommandSupport} for file size):
 * direct unit coverage of the two tracker-resolution seams — {@link
 * TrackerResolution#resolveTracker}, the funnel every claiming command resolves through (FR4 of
 * fix-claim-epoch-fence), and {@link TrackerResolution#resolveReadOnlyTracker}, the registry
 * lookup {@code board} and {@code dashboard} use, which hold no tenure record because they never
 * claim.
 *
 * <p>Implements FR9, FR17 of add-tracker-port; FR4 of fix-claim-epoch-fence.
 */
class TrackerResolutionSpec extends Specification {

    // FR4 of fix-claim-epoch-fence: the claiming funnel builds the adapter over the bundle's own
    //     tenure record AND records into that same record, so a claim and the commits stamped under
    //     it can never be described by two different books.
    def "resolveTracker builds the adapter over the bundle's tenure record and records claims into it"() {
        given: 'a factory that answers the epoch-aware create, and a tenure record from a bundle'
        def trackerConfig = new TrackerConfig('fixture', 3, [:])
        def book = new ClaimEpochBook()
        def live = Mock(Tracker)
        def factory = Mock(TrackerAdapterFactory)

        when:
        def resolved = TrackerResolution.resolveTracker(
                factory, trackerConfig, MapSecretsProvider.NONE, 'gnomish-factory-a1', book)

        then: 'the adapter was handed the SAME book, so its own writers stamp the live tenure'
        1 * factory.create(MapSecretsProvider.NONE, trackerConfig, 'gnomish-factory-a1', book) >> live

        when: 'a claim is acquired through the resolved tracker'
        def claimed = resolved.claim(new TaskRef('PROJ-1'), 'gnomish-factory-a1')

        then: 'the epoch the tracker issued landed in that same book'
        1 * live.claim(new TaskRef('PROJ-1'), 'gnomish-factory-a1') >> new ClaimResult.Acquired(new ClaimEpoch(7))
        claimed == new ClaimResult.Acquired(new ClaimEpoch(7))
        book.epochFor('PROJ-1').orElse(null) == new ClaimEpoch(7)
    }

    def "resolveReadOnlyTracker resolves the registered factory and builds a live Tracker from it"() {
        given: 'a registry with one factory registered for the configured type'
        def trackerConfig = new TrackerConfig('fixture', 3, [:])
        def tracker = Mock(Tracker)
        def factory = Mock(TrackerAdapterFactory) {
            create(MapSecretsProvider.NONE, trackerConfig, 'gnomish-factory-a1') >> tracker
        }
        def registry = [fixture: factory]

        when:
        def resolved = TrackerResolution.resolveReadOnlyTracker(
                trackerConfig, registry, MapSecretsProvider.NONE, 'gnomish-factory-a1')

        then: 'a reader is left unwrapped: it never claims, so there is nothing to record'
        resolved == tracker
    }

    def "resolveReadOnlyTracker refuses when no factory is registered for the configured type"() {
        given: 'an empty registry'
        def trackerConfig = new TrackerConfig('unknown-type', 3, [:])

        when:
        TrackerResolution.resolveReadOnlyTracker(trackerConfig, [:], MapSecretsProvider.NONE, 'gnomish-factory-a1')

        then:
        def ex = thrown(UsageException)
        ex.message.contains('unknown-type')
    }

    def "the refusal lists the supported tracker types, sorted, so the operator hint is actionable"() {
        given: 'a registry with several known types in non-sorted insertion order'
        def trackerConfig = new TrackerConfig('unknown-type', 3, [:])
        def registry = [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)]

        when:
        TrackerResolution.resolveReadOnlyTracker(trackerConfig, registry, MapSecretsProvider.NONE, 'gnomish-factory-a1')

        then: 'FR17: the message names the registered types as a stable, sorted list'
        def ex = thrown(UsageException)
        ex.message.contains('github, inmemory')
    }

    def "supportedTypes renders the registered type keys sorted and comma-joined"() {
        expect:
        TrackerResolution.supportedTypes(registry) == expected

        where:
        registry || expected
        [:] || ''
        [github: Mock(TrackerAdapterFactory)] || 'github'
        [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)] || 'github, inmemory'
    }
}
