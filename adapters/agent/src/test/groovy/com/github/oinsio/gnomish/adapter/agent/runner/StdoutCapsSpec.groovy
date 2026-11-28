package com.github.oinsio.gnomish.adapter.agent.runner

import spock.lang.Specification

/** FR8, NFR-R1 of extract-agent-round-runner: the stdout caps hold what they claim and refuse what cannot work. */
class StdoutCapsSpec extends Specification {

    def "NFR-R1: the factory's caps are lines of 1 MiB and 32 MiB of lines kept"() {
        expect:
        StdoutCaps.DEFAULTS == new StdoutCaps(1024 * 1024, 32L * 1024 * 1024)
    }

    def "NFR-R1: a line cap of #line and a retained cap of #retained are refused"() {
        when:
        new StdoutCaps(line, retained)

        then:
        def thrownBy = thrown(IllegalArgumentException)
        thrownBy.message.contains(words)

        where:
        line | retained || words
        0 | 10 || 'lineBytes must be positive, was 0'
        5 | 9 || 'retainedBytes must be at least twice lineBytes, was 9 for lines of 5'
    }

    def "NFR-R1: the smallest caps that work are accepted"() {
        expect:
        new StdoutCaps(1, 2).retainedBytes() == 2
    }
}
