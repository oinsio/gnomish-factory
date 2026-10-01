package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * {@link ConfigurationViolationsException}: one report of every violation, and the usage-error exit
 * code it carries itself, since no exit-code mapper bean exists when it is thrown (FR7, NFR-O2).
 *
 * <p>Implements FR7, NFR-O2 of add-project-registry.
 */
class ConfigurationViolationsExceptionSpec extends Specification {

    // FR7: the report names the count and lists every line
    def "FR7: the message is the report: a heading with the count, then one line per violation"() {
        expect:
        new ConfigurationViolationsException(lines).message == report

        where:
        lines || report
        ['a'] || 'gnomish did not start: 1 configuration problem (fix every line, then run the command again)\n  - a'
        ['a', 'b'] || 'gnomish did not start: 2 configuration problems (fix every line, then run the command again)\n  - a\n  - b'
    }

    // FR7: the lines stay readable one by one, in the order found
    def "FR7: the violations are kept in order"() {
        expect:
        new ConfigurationViolationsException(['first', 'second']).violations() == ['first', 'second']
    }

    // FR7: no new exit code — the usage-error code
    def "FR7: the exit code is the usage-error code 2"() {
        expect:
        new ConfigurationViolationsException(['a']).exitCode == 2
    }

    def "a report with no violation is refused"() {
        when:
        new ConfigurationViolationsException([])

        then:
        thrown(IllegalArgumentException)
    }
}
