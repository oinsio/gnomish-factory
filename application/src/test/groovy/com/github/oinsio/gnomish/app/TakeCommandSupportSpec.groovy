package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * {@link TakeCommandSupport} (task 5.13): direct unit coverage of {@link
 * TakeCommandSupport#resolveTracker}, the small {@code resolveFactory().create(...)} combinator
 * seam that {@link TakeCommand} itself does not call directly (it inlines the same two steps to
 * keep its own "unknown tracker.type" refusal path readable) but that remains a documented,
 * reusable seam for other callers of {@link TakeCommandSupport}.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
class TakeCommandSupportSpec extends Specification {

    def "resolveTracker resolves the registered factory and builds a live Tracker from it"() {
        given: 'a registry with one factory registered for the configured type'
        def trackerConfig = new TrackerConfig('fixture', 3, [:])
        def tracker = Mock(Tracker)
        def factory = Mock(TrackerAdapterFactory) {
            create(trackerConfig, 'gnomish-factory-a1') >> tracker
        }
        def registry = [fixture: factory]

        when:
        def resolved = TakeCommandSupport.resolveTracker(trackerConfig, registry, 'gnomish-factory-a1')

        then:
        resolved == tracker
    }

    def "resolveTracker refuses when no factory is registered for the configured type"() {
        given: 'an empty registry'
        def trackerConfig = new TrackerConfig('unknown-type', 3, [:])

        when:
        TakeCommandSupport.resolveTracker(trackerConfig, [:], 'gnomish-factory-a1')

        then:
        def ex = thrown(UsageException)
        ex.message.contains('unknown-type')
    }

    def "the refusal lists the supported tracker types, sorted, so the operator hint is actionable"() {
        given: 'a registry with several known types in non-sorted insertion order'
        def trackerConfig = new TrackerConfig('unknown-type', 3, [:])
        def registry = [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)]

        when:
        TakeCommandSupport.resolveTracker(trackerConfig, registry, 'gnomish-factory-a1')

        then: 'FR17: the message names the registered types as a stable, sorted list'
        def ex = thrown(UsageException)
        ex.message.contains('github, inmemory')
    }

    def "supportedTypes renders the registered type keys sorted and comma-joined"() {
        expect:
        TakeCommandSupport.supportedTypes(registry) == expected

        where:
        registry || expected
        [:] || ''
        [github: Mock(TrackerAdapterFactory)] || 'github'
        [inmemory: Mock(TrackerAdapterFactory), github: Mock(TrackerAdapterFactory)] || 'github, inmemory'
    }
}
