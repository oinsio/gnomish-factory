package com.github.oinsio.gnomish.logtext

import spock.lang.Specification

/**
 * FR4 of harden-logging-observability: a suppressor tells one fault from another by its reason
 * string, so the reason must carry the type <em>and</em> the fault's own words — the type alone
 * cannot distinguish two {@code RuntimeException}s, and a message-only reason vanishes for the
 * faults that carry none.
 */
class FailureReasonSpec extends Specification {

    def "FR4: a fault with words is identified by its type and its words"() {
        expect:
        FailureReason.of(new IllegalStateException('sink boom')) == 'java.lang.IllegalStateException: sink boom'
    }

    def "FR4: a fault with no words is identified by its type alone, never by a 'null' suffix"() {
        when:
        def reason = FailureReason.of(new RuntimeException())

        then:
        reason == 'java.lang.RuntimeException'
        !reason.contains('null')
    }

    def "FR4: two different faults are two different reasons, so a streak restarts on a change"() {
        expect:
        FailureReason.of(new RuntimeException('5xx')) != FailureReason.of(new RuntimeException('connection reset'))
        FailureReason.of(new RuntimeException('5xx')) != FailureReason.of(new IllegalStateException('5xx'))
    }
}
