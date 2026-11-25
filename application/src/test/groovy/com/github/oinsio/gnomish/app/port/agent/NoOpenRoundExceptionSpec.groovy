package com.github.oinsio.gnomish.app.port.agent

import spock.lang.Specification

/**
 * FR10 of extract-agent-round-runner (design D11, D14 as amended 2026-11-25): the refusal of a poll
 * with no open round names the round it asked for, so the poll's {@code failed/infrastructure} names
 * the missing round.
 */
class NoOpenRoundExceptionSpec extends Specification {

    def "a poll with no open round names the stage and the attempt it asked for"() {
        expect:
        new NoOpenRoundException('build', 3).message.startsWith("no round of stage 'build' attempt 3 is open to poll in")
    }
}
