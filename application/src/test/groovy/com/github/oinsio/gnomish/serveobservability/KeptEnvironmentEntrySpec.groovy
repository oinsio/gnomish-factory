package com.github.oinsio.gnomish.serveobservability

import spock.lang.Specification

/**
 * {@link KeptEnvironmentEntry}, task 6.1 of add-serve-sandbox-lifecycle (NFR-O1): the inventory
 * row's own invariants — an unnamed task or a negative span would render as a broken table rather
 * than fail where it was built.
 */
class KeptEnvironmentEntrySpec extends Specification {

    def "carries the task key and both spans verbatim"() {
        given:
        def entry = new KeptEnvironmentEntry('task-42', 172800L, 432000L)

        expect:
        entry.taskKey() == 'task-42'
        entry.ageSeconds() == 172800L
        entry.untilReapSeconds() == 432000L
    }

    def "a blank task key is rejected"() {
        when:
        new KeptEnvironmentEntry(taskKey, 1L, 1L)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'KeptEnvironmentEntry.taskKey must not be blank'

        where:
        taskKey << ['', '   ']
    }

    def "a negative span is rejected, naming the component"() {
        when:
        new KeptEnvironmentEntry('task-42', age, untilReap)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "KeptEnvironmentEntry.${component} must not be negative"

        where:
        age | untilReap | component
        -1L | 0L | 'ageSeconds'
        0L | -1L | 'untilReapSeconds'
    }
}
