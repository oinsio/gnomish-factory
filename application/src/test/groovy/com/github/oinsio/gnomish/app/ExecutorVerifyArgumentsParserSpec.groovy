package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * FR12, UX2 of define-executor-contract: {@code gnomish executor verify <program>
 * [--pending-settings=<json>]}. The verb and the program are the only two positional words; the
 * pending settings are one JSON object read into plain values, empty when absent; anything else is
 * a usage error naming the form.
 */
class ExecutorVerifyArgumentsParserSpec extends Specification implements ApplicationArgumentsFixture {

    // FR12: the program, and the pending settings when given
    def "FR12: #argv parses to program #program with pending settings #settings"() {
        expect:
        new ExecutorVerifyArgumentsParser().parse(args(argv as String[])) == new ExecutorVerifyArguments(program, settings)

        where:
        argv || program | settings
        [
            'executor',
            'verify',
            '/opt/acme/run'
        ] || '/opt/acme/run' | [:]
        [
            'executor',
            'verify',
            'acme',
            '--pending-settings={"defer":true,"n":2,"tags":["a"]}'
        ] || 'acme' | [defer: true, n: 2, tags: ['a']]
        [
            '--pending-settings={}',
            'executor',
            'verify',
            'acme'
        ] || 'acme' | [:]
    }

    // UX2: a malformed command line is refused with the form, before anything runs
    def "UX2: #argv is refused naming #fragment"() {
        when:
        new ExecutorVerifyArgumentsParser().parse(args(argv as String[]))

        then:
        UsageException refused = thrown()
        refused.message.contains(fragment)

        where:
        argv || fragment
        ['executor'] || 'gnomish executor verify <program>'
        ['executor', 'check', 'acme'] || "got 'gnomish executor check acme'"
        ['executor', 'verify'] || 'gnomish executor verify <program>'
        [
            'executor',
            'verify',
            'acme',
            'extra'
        ] || 'gnomish executor verify <program>'
        [
            'executor',
            'verify',
            'acme',
            '--json'
        ] || 'unknown option --json'
        [
            'executor',
            'verify',
            'acme',
            '--pending-settings=[1]'
        ] || 'must be one JSON object'
        [
            'executor',
            'verify',
            'acme',
            '--pending-settings=7'
        ] || 'must be one JSON object'
        [
            'executor',
            'verify',
            'acme',
            '--pending-settings={"defer":'
        ] || 'is not JSON'
        [
            'executor',
            'verify',
            'acme',
            '--pending-settings={"defer":null}'
        ] || "sets 'defer' to null"
        [
            'executor',
            'verify',
            'acme',
            '--pending-settings'
        ] || '--pending-settings requires a value'
    }

    // FR12: the arguments refuse a blank program, whoever builds them
    def "FR12: a blank program is refused"() {
        when:
        new ExecutorVerifyArguments(' ', [:])

        then:
        thrown(IllegalArgumentException)
    }

    // UX2: the run's end names how many cases failed
    def "UX2: a failed run names its #count failed case#plural"() {
        expect:
        new ExecutorVerifyFailedException(count).message == "the candidate failed ${count} conformance case${plural}"

        where:
        count | plural
        1 | ''
        2 | 's'
    }
}
