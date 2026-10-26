package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * {@link ConfigurationViolations}: the one value behind the startup refusal and {@code doctor}'s
 * listing (FR7, design D1 of add-doctor-command).
 */
class ConfigurationViolationsSpec extends Specification {

    // FR7: the refusing form is the report of exactly these lines
    def "FR7: the refusal reports every line, in order"() {
        when:
        def report = new ConfigurationViolations(['first', 'second']).exception()

        then:
        report.violations() == ['first', 'second']
        report.exitCode == 2
    }

    // FR7: nothing found is no report
    def "FR7: an empty value has nothing to refuse on"() {
        expect:
        ConfigurationViolations.NONE.isEmpty()
        !new ConfigurationViolations(['a']).isEmpty()

        when:
        ConfigurationViolations.NONE.exception()

        then:
        thrown(IllegalArgumentException)
    }

    // FR7: the log file's violation follows the check's
    def "FR7: plus keeps the order found"() {
        expect:
        new ConfigurationViolations(['a']).plus(new ConfigurationViolations(['b', 'c'])).lines() == ['a', 'b', 'c']
        ConfigurationViolations.NONE.plus(ConfigurationViolations.NONE).isEmpty()
    }

    // FR7: the value cannot be changed behind the refusal's back
    def "FR7: the lines are copied and unmodifiable"() {
        given:
        def source = ['a']
        def violations = new ConfigurationViolations(source)

        when:
        source << 'b'

        then:
        violations.lines() == ['a']

        when:
        violations.lines().add('c')

        then:
        thrown(UnsupportedOperationException)
    }
}
