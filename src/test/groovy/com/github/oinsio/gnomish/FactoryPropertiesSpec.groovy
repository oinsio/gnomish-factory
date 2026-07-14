package com.github.oinsio.gnomish

import spock.lang.Specification

/**
 * FactoryProperties: immutable typed configuration record (design D4).
 * Validation is plain Java in the compact constructor — no Spring context
 * needed here; constructor binding is covered by the context-level spec.
 * Contract: null/blank instanceId throws IllegalArgumentException whose
 * message names the external property {@code factory.instance-id}.
 * Implements FR3 of add-project-skeleton.
 */
class FactoryPropertiesSpec extends Specification {

    // FR3: valid configuration binds — the record exposes the constructor value
    def "valid instance-id is exposed by the record accessor"() {
        when: 'a properties record is created with a valid instance-id'
        def properties = new FactoryProperties('factory-01')

        then: 'the accessor returns exactly the constructed value'
        properties.instanceId() == 'factory-01'
    }

    // FR3: invalid configuration fails fast — the error names the property
    def "instance-id of #description is rejected with the property name in the message"() {
        when: 'a properties record is created with an invalid instance-id'
        new FactoryProperties(invalidInstanceId)

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
