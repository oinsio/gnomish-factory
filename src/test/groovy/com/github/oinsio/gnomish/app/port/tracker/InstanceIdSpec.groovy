package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * InstanceId: the composite {@code <name>-<suffix>} process identity (design
 * D6). Implements FR9 of add-tracker-port.
 */
class InstanceIdSpec extends Specification {

    // FR9: the composite string exposes name and suffix joined by a separator
    def "composite value joins name and suffix with a hyphen"() {
        expect:
        new InstanceId('gnomish-factory', 'x7k2q1').value() == 'gnomish-factory-x7k2q1'
    }

    // FR9: name is rejected blank, matching the blank-check idiom used elsewhere in this package
    def "blank name is rejected with the component name in the message"() {
        when:
        new InstanceId(name, 'x7k2q1')

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('InstanceId.name')

        where:
        name << ['', '   ', '\t', ' \n']
    }

    // FR9: suffix is rejected blank the same way
    def "blank suffix is rejected with the component name in the message"() {
        when:
        new InstanceId('gnomish-factory', suffix)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('InstanceId.suffix')

        where:
        suffix << ['', '   ', '\t', ' \n']
    }

    // FR9, D6: the generated suffix matches the documented shape: 6-char lowercase base36
    def "generate produces a 6-character lowercase base36 suffix"() {
        when:
        def id = InstanceId.generate('gnomish-factory')

        then:
        id.name() == 'gnomish-factory'
        id.suffix().length() == 6
        id.suffix() ==~ /[0-9a-z]{6}/
        id.value() == "gnomish-factory-${id.suffix()}"
    }

    /*
     * FR9, D6: "uniqueness across same-name processes" is a probabilistic
     * property of the 6-char base36 suffix space (36^6 ≈ 2.18 billion
     * possibilities): a passing run is strong statistical evidence, not a
     * mathematical proof, that two same-name processes started around the
     * same time get distinct ids. N=2000 keeps the birthday-bound collision
     * chance negligible (~2000^2 / (2 * 2.18e9) ≈ 0.09%) while keeping the
     * spec fast.
     */
    def "generating many InstanceIds for the same name yields statistically distinct composite values"() {
        given:
        def name = 'gnomish-factory'
        def sampleSize = 2000

        when:
        def values = (1..sampleSize).collect { InstanceId.generate(name).value() }

        then:
        values.toSet().size() == sampleSize
    }
}
