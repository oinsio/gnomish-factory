package com.github.oinsio.gnomish

import java.time.Duration
import spock.lang.Specification

/**
 * FactoryProperties, the Duration-deadline half (see {@link FactoryPropertiesSpec} for the split
 * by capability): every timeout knob defaults when unset, is exposed unchanged when set, and
 * rejects a non-positive value at startup naming the external property.
 *
 * <p>agent-cli-tail-drain-grace: implements FR7, design D2 of fix-round-stdout-drain.
 *
 * <p>git-network-timeout / docker-command-timeout / check-command-timeout: the three subprocess
 * deadlines. Implements FR5, UX1, design D8 of bound-subprocess-commands.
 */
class FactoryPropertiesTimeoutSpec extends Specification {

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

    // FR5, UX1, D8 of bound-subprocess-commands: each subprocess deadline is an overridable
    // installation property with a documented default
    def "#property defaults to #expected when unset"() {
        when: 'a properties record is created through a constructor that predates the deadlines'
        def properties = new FactoryProperties('factory-01', 'claude', null, [], null, null, null)

        then: 'the accessor returns the documented default'
        accessor(properties) == expected

        where:
        property | accessor | expected
        'factory.git-network-timeout' | {
            it.gitNetworkTimeout()
        } | Duration.ofMinutes(5)
        'factory.docker-command-timeout' | {
            it.dockerCommandTimeout()
        } | Duration.ofMinutes(5)
        'factory.check-command-timeout' | {
            it.checkCommandTimeout()
        } | Duration.ofMinutes(30)
    }

    // FR5/D8: an explicit deadline overrides the default — the knob an operator on a slow link raises
    def "#property of an explicit value is exposed unchanged"() {
        when:
        def properties = build(Duration.ofSeconds(90))

        then:
        accessor(properties) == Duration.ofSeconds(90)

        where:
        property << [
            'factory.git-network-timeout',
            'factory.docker-command-timeout',
            'factory.check-command-timeout'
        ]
        build << [
            { d ->
                new FactoryProperties('factory-01', 'claude', null, [], null, null, null, d, null, null)
            },
            { d ->
                new FactoryProperties('factory-01', 'claude', null, [], null, null, null, null, d, null)
            },
            { d ->
                new FactoryProperties('factory-01', 'claude', null, [], null, null, null, null, null, d)
            }
        ]
        accessor << [
            { it.gitNetworkTimeout() },
            { it.dockerCommandTimeout() },
            { it.checkCommandTimeout() }
        ]
    }

    // FR5/D8: a deadline of zero or less would kill every command before it started — a startup
    //     error naming the property the operator set, not a per-command mystery
    def "#property of a non-positive value is rejected with the property name"() {
        when: 'the deadline is zero'
        build(Duration.ZERO)

        then:
        def zeroFailure = thrown(IllegalArgumentException)
        zeroFailure.message.contains(property)

        when: 'the deadline is negative'
        build(Duration.ofSeconds(-1))

        then:
        def negativeFailure = thrown(IllegalArgumentException)
        negativeFailure.message.contains(property)

        where:
        property << [
            'factory.git-network-timeout',
            'factory.docker-command-timeout',
            'factory.check-command-timeout'
        ]
        build << [
            { d ->
                new FactoryProperties('factory-01', 'claude', null, [], null, null, null, d, null, null)
            },
            { d ->
                new FactoryProperties('factory-01', 'claude', null, [], null, null, null, null, d, null)
            },
            { d ->
                new FactoryProperties('factory-01', 'claude', null, [], null, null, null, null, null, d)
            }
        ]
    }
}
