package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * HumanReply: a human reply comment collected after the last ack, distinct
 * from the engine-facing domain.engine.Decision it is later turned into
 * (FR12). Implements FR12 of add-tracker-port.
 */
class HumanReplySpec extends Specification {

    // FR12: body and postedAt round-trip exactly as constructed
    def "exposes body and postedAt exactly as constructed"() {
        given:
        def when = Instant.parse('2026-07-16T10:15:30Z')

        when:
        def reply = new HumanReply('use library X, not Y', when)

        then:
        reply.body() == 'use library X, not Y'
        reply.postedAt() == when
    }

    // FR12: a reply with no message carries nothing to act on
    def "blank body is rejected with the component name in the message"() {
        when:
        new HumanReply(body, Instant.parse('2026-07-16T10:15:30Z'))

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('HumanReply.body')

        where:
        body << ['', '   ', '\t', ' \n']
    }

    // FR12: replies are values — equal content means equal replies
    def "replies with the same components are equal values"() {
        given:
        def when = Instant.parse('2026-07-16T10:15:30Z')

        expect:
        new HumanReply('ship it', when) == new HumanReply('ship it', when)

        and: 'a differing postedAt makes them unequal'
        new HumanReply('ship it', when) != new HumanReply('ship it', when.plusSeconds(1))
    }
}
