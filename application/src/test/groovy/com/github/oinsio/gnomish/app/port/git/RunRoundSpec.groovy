package com.github.oinsio.gnomish.app.port.git

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.RoundToken
import spock.lang.Specification

/**
 * FR19 of migrate-verify-to-executor-contract (design D22, "The run's round cell has one owner"):
 * each run preparation builds one round identity — a fresh cell beside the pending verification the
 * resume path passed in.
 */
class RunRoundSpec extends Specification {

    def "FR19: every run gets a fresh cell of its own, beside the pending verification it was handed"() {
        given:
        def pending = new PendingVerification('snap-1', 'build', 0, RoundToken.of('0a1b2c'), RoundClose.VERIFIABLE, new AnswerFileReading.Absent(), 'snap-1', ['snap-1'])

        when:
        def first = RunRound.of(pending)
        def second = RunRound.of(null)

        then:
        first.pending().is(pending)
        second.pending() == null
        first.rounds() != null
        !first.rounds().is(second.rounds())
    }
}
