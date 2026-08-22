package com.github.oinsio.gnomish

import java.time.Duration
import spock.lang.Specification

/**
 * FactoryProperties: immutable typed configuration record (design D4).
 * Validation is plain Java in the compact constructor — no Spring context
 * needed here; constructor binding is covered by the context-level spec.
 * Contract: an unset instance-name defaults to "gnomish-factory" (design D5,
 * D6); an explicitly blank instance-name still fails fast, naming the
 * external property {@code factory.instance-name}.
 * Implements FR3 of add-project-skeleton.
 *
 * <p>agentCliBinary and agentCliEnvPassthrough: installation-level executor
 * config (never in the manifest). Implements FR11, D7 of add-agent-executor.
 *
 * <p>tracker: the abort-backoff base/cap Duration defaults (design D5, D10).
 * Implements FR17 of add-tracker-port.
 */
class FactoryPropertiesSpec extends Specification {

    // FR3: valid configuration binds — the record exposes the constructor value
    def "explicit instance-name is exposed by the record accessor"() {
        when: 'a properties record is created with an explicit instance-name'
        def properties = new FactoryProperties('factory-01', 'claude', [], null, null)

        then: 'the accessor returns exactly the constructed value'
        properties.instanceName() == 'factory-01'
    }

    // FR3/D5/D6: instance-name defaults to "gnomish-factory" when unset
    def "instance-name defaults to gnomish-factory when null"() {
        when: 'a properties record is created without an explicit instance-name'
        def properties = new FactoryProperties(null, 'claude', [], null, null)

        then: 'the accessor returns the neutral default'
        properties.instanceName() == 'gnomish-factory'
    }

    // FR3/D5: an explicitly blank instance-name is still a configuration mistake
    def "instance-name of #description is rejected with the property name in the message"() {
        when: 'a properties record is created with a blank instance-name'
        new FactoryProperties(blankInstanceName, 'claude', [], null, null)

        then: 'construction fails and the message names factory.instance-name'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.instance-name')

        where:
        blankInstanceName | description
        '' | 'empty string'
        '   ' | 'spaces only'
        '\t\n' | 'other whitespace'
    }

    // FR7/D2 of fix-round-stdout-drain: the tail-drain grace defaults to 5 seconds
    def "agent-cli-tail-drain-grace defaults to five seconds when unset"() {
        when: 'a properties record is created through a constructor that predates the grace'
        def properties = new FactoryProperties('factory-01', 'claude', [], null, null)

        then: 'the accessor returns the safe default'
        properties.agentCliTailDrainGrace() == Duration.ofSeconds(5)
    }

    // FR7: an explicit grace overrides the default
    def "agent-cli-tail-drain-grace of an explicit value is exposed unchanged"() {
        when:
        def properties = new FactoryProperties(
                'factory-01', 'claude', Duration.ofSeconds(30), [], null, null, null)

        then:
        properties.agentCliTailDrainGrace() == Duration.ofSeconds(30)
    }

    // FR7/D2: a non-positive grace is a startup error, before any dialog
    def "agent-cli-tail-drain-grace of #description is rejected with the property name"() {
        when:
        new FactoryProperties('factory-01', 'claude', grace, [], null, null, null)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.agent-cli-tail-drain-grace')

        where:
        grace | description
        Duration.ZERO | 'zero'
        Duration.ofSeconds(-1) | 'a negative duration'
    }

    // FR11/D7: CLI binary path defaults to "claude" from PATH when unset
    def "agent-cli-binary defaults to claude when null"() {
        when: 'a properties record is created without an explicit agent-cli-binary'
        def properties = new FactoryProperties('factory-01', null, [], null, null)

        then: 'the accessor returns the default binary name'
        properties.agentCliBinary() == 'claude'
    }

    // FR11/D7: an explicit CLI binary path overrides the default
    def "agent-cli-binary of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit agent-cli-binary'
        def properties = new FactoryProperties('factory-01', '/usr/local/bin/claude', [], null, null)

        then: 'the accessor returns exactly the configured value'
        properties.agentCliBinary() == '/usr/local/bin/claude'
    }

    // FR11/D7: env passthrough defaults to an empty list when unset
    def "agent-cli-env-passthrough defaults to an empty list when null"() {
        when: 'a properties record is created without an explicit env passthrough list'
        def properties = new FactoryProperties('factory-01', 'claude', null, null, null)

        then: 'the accessor returns an empty list'
        properties.agentCliEnvPassthrough() == []
    }

    // FR11/D7: explicit env passthrough names are exposed unchanged
    def "agent-cli-env-passthrough of explicit names is exposed unchanged"() {
        when: 'a properties record is created with explicit env passthrough names'
        def properties = new FactoryProperties('factory-01', 'claude', [
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN'
        ], null, null)

        then: 'the accessor returns exactly the configured list'
        properties.agentCliEnvPassthrough() == [
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN'
        ]
    }

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

    // FR3: the properties type is an immutable record without setters
    def "the properties type is an immutable record without setter methods"() {
        given: 'the FactoryProperties class'
        def type = FactoryProperties

        expect: 'it is a Java record (final, all components final)'
        type.isRecord()

        and: 'no public method follows the mutable setter convention'
        type.methods.every {
            !(it.name.startsWith('set') && it.parameterCount> 0)
        }
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
