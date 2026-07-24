package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerAdapterFactory
import spock.lang.Specification

/**
 * {@link TrackerAdapterConfiguration} (task 5.15): the {@code trackerAdapterRegistry} bean is
 * populated with the real GitHub and in-memory adapter factories, keyed by {@code tracker.type}
 * spelling ({@code github}, {@code inmemory}) matching {@code
 * PipelineModelBuilder.TRACKER_VALIDATORS}'s own key.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
class TrackerAdapterConfigurationSpec extends Specification {

    def "the registry is wired with github and inmemory adapter factories"() {
        given:
        def configuration = new TrackerAdapterConfiguration()

        when:
        def registry = configuration.trackerAdapterRegistry()

        then:
        registry.keySet() == ['github', 'inmemory'] as Set
        registry['github'] instanceof GithubTrackerAdapterFactory
        registry['inmemory'] instanceof InMemoryTrackerAdapterFactory
    }
}
