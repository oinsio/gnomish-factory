package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * FactoryProperties: immutable typed configuration record (design D4).
 * Validation is plain Java in the compact constructor — no Spring context
 * needed here; constructor binding is covered by the context-level spec.
 * Contract: null/blank instanceId throws IllegalArgumentException whose
 * message names the external property {@code factory.instance-id}.
 * Implements FR3 of add-project-skeleton.
 *
 * <p>agentCliBinary and agentCliEnvPassthrough: installation-level executor
 * config (never in the manifest). Implements FR11, D7 of add-agent-executor.
 */
class FactoryPropertiesSpec extends Specification {

    // FR3: valid configuration binds — the record exposes the constructor value
    def "valid instance-id is exposed by the record accessor"() {
        when: 'a properties record is created with a valid instance-id'
        def properties = new FactoryProperties('factory-01', 'claude', [])

        then: 'the accessor returns exactly the constructed value'
        properties.instanceId() == 'factory-01'
    }

    // FR11/D7: CLI binary path defaults to "claude" from PATH when unset
    def "agent-cli-binary defaults to claude when null"() {
        when: 'a properties record is created without an explicit agent-cli-binary'
        def properties = new FactoryProperties('factory-01', null, [])

        then: 'the accessor returns the default binary name'
        properties.agentCliBinary() == 'claude'
    }

    // FR11/D7: an explicit CLI binary path overrides the default
    def "agent-cli-binary of an explicit value is exposed unchanged"() {
        when: 'a properties record is created with an explicit agent-cli-binary'
        def properties = new FactoryProperties('factory-01', '/usr/local/bin/claude', [])

        then: 'the accessor returns exactly the configured value'
        properties.agentCliBinary() == '/usr/local/bin/claude'
    }

    // FR11/D7: env passthrough defaults to an empty list when unset
    def "agent-cli-env-passthrough defaults to an empty list when null"() {
        when: 'a properties record is created without an explicit env passthrough list'
        def properties = new FactoryProperties('factory-01', 'claude', null)

        then: 'the accessor returns an empty list'
        properties.agentCliEnvPassthrough() == []
    }

    // FR11/D7: explicit env passthrough names are exposed unchanged
    def "agent-cli-env-passthrough of explicit names is exposed unchanged"() {
        when: 'a properties record is created with explicit env passthrough names'
        def properties = new FactoryProperties('factory-01', 'claude', [
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN'
        ])

        then: 'the accessor returns exactly the configured list'
        properties.agentCliEnvPassthrough() == [
            'ANTHROPIC_BASE_URL',
            'ANTHROPIC_AUTH_TOKEN'
        ]
    }

    // FR3: invalid configuration fails fast — the error names the property
    def "instance-id of #description is rejected with the property name in the message"() {
        when: 'a properties record is created with an invalid instance-id'
        new FactoryProperties(invalidInstanceId, 'claude', [])

        then: 'construction fails and the message names factory.instance-id'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('factory.instance-id')

        where:
        invalidInstanceId | description
        null              | 'null'
        ''                | 'empty string'
        '   '             | 'spaces only'
        '\t\n'            | 'other whitespace'
    }

    // FR3: the properties object is immutable — a record with no setters
    def "the properties type is an immutable record without setter methods"() {
        given: 'the FactoryProperties class'
        def type = FactoryProperties

        expect: 'it is a Java record (final, all components final)'
        type.isRecord()

        and: 'no public method follows the mutable setter convention'
        type.methods.every { !(it.name.startsWith('set') && it.parameterCount > 0) }
    }
}
