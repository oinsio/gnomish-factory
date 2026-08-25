package com.github.oinsio.gnomish

import java.time.Duration
import spock.lang.Specification

/**
 * FactoryProperties, the nested-sections half (see {@link FactoryPropertiesSpec} for the split by
 * capability): the tracker abort-backoff subsection and the open-ended check-provider subsections.
 *
 * <p>tracker: the abort-backoff base/cap Duration defaults (design D5, D10).
 * Implements FR17 of add-tracker-port.
 *
 * <p>check: raw provider subsections core never interprets.
 * Implements FR26 of add-sandbox-core; FR5, design D12 of add-plugin-architecture.
 */
class FactoryPropertiesSectionsSpec extends Specification {

    // FR17/D5/D10: tracker abort-backoff base/cap default to 2m/1h when unset
    def "tracker abort-backoff base and cap default to 2m/1h when unset"() {
        when: 'a properties record is created without an explicit tracker section'
        def properties = new FactoryProperties('factory-01', 'claude', [], null, null)

        then: 'the accessor returns the design D5 defaults'
        properties.tracker().abortBackoffBase() == Duration.ofMinutes(2)
        properties.tracker().abortBackoffCap() == Duration.ofHours(1)
    }

    // FR17/D5/D10: explicit tracker abort-backoff base/cap are exposed unchanged
    def "tracker abort-backoff base and cap of #base/#cap are exposed unchanged"() {
        when: 'a properties record is created with an explicit tracker section'
        def properties = new FactoryProperties(
                'factory-01', 'claude', [], new FactoryProperties.Tracker(base, cap), null)

        then: 'the accessor returns exactly the configured values'
        properties.tracker().abortBackoffBase() == base
        properties.tracker().abortBackoffCap() == cap

        where:
        base | cap
        Duration.ofMinutes(5) | Duration.ofHours(2)
        Duration.ofSeconds(30) | Duration.ofMinutes(45)
        Duration.ofMillis(1) | Duration.ofDays(1)
    }

    // FR17/D5/D10: a partially-configured tracker section still defaults the other half
    def "tracker abort-backoff base defaults when only cap is configured"() {
        when: 'a properties record is created with only the cap explicitly set'
        def properties = new FactoryProperties(
                'factory-01', 'claude', [], new FactoryProperties.Tracker(null, Duration.ofHours(3)), null)

        then: 'the base still defaults, the cap is the configured value'
        properties.tracker().abortBackoffBase() == Duration.ofMinutes(2)
        properties.tracker().abortBackoffCap() == Duration.ofHours(3)
    }

    // FR26 of add-sandbox-core; FR5 of add-plugin-architecture: no check provider configured is an
    //     empty section, not a vendor-shaped record with every key unset
    def "check section defaults to no configured provider"() {
        expect:
        new FactoryProperties(null, null, null, null, null).check().isEmpty()
    }

    // FR5, design D12 of add-plugin-architecture: the section is an open-ended map of provider
    //     subsections carried as raw content — core interprets no key, so a provider it has never
    //     heard of binds exactly like the bundled one
    def "a configured provider subsection is carried through verbatim"() {
        when:
        def properties = new FactoryProperties(null, null, null, null,
                [github: [('api-url'): 'https://api.github.com', repo: 'acme/widgets'],
                    sonar: [('api-url'): 'https://sonar.example']])

        then:
        properties.check().keySet() == ['github', 'sonar'] as Set
        properties.check()['github'] == [('api-url'): 'https://api.github.com', repo: 'acme/widgets']
        properties.check()['sonar'] == [('api-url'): 'https://sonar.example']
    }

    // FR5 of add-plugin-architecture: a subsection key with no content binds to an empty map rather
    //     than a null the providers would each have to defend against
    def "a provider subsection with no content binds to an empty map"() {
        when:
        def properties = new FactoryProperties(null, null, null, null, [github: null])

        then:
        properties.check()['github'] == [:]
    }

    // FR5 of add-plugin-architecture: the bound section is defensively copied, so a caller that
    //     mutates the map it passed cannot reshape a provider's configuration afterwards
    def "the bound check section is immutable"() {
        given:
        Map<String, Map<String, Object>> source = [github: [('api-url'): 'https://api.github.com']]
        def properties = new FactoryProperties(null, null, null, null, source)

        when:
        source['sonar'] = [:] as Map<String, Object>

        then:
        properties.check().keySet() == ['github'] as Set

        when:
        properties.check()['github'].put('repo', 'acme/widgets')

        then:
        thrown(UnsupportedOperationException)
    }
}
