package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * AttemptRecord's {@code stop} component (design D3): a stop rides only the round whose result
 * names it; None rides any round. Implements FR5 of make-checkpoint-gate-durable.
 */
class AttemptRecordStopSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-10-07T09:00:00Z')

    private static final Stop DECISION = new Stop.DecisionNeeded(UntrustedText.agent('which db?'), [UntrustedText.agent('pg')])

    private static final Stop CANNOT_VERIFY = new Stop.CannotVerify(new CheckRef(0, UntrustedText.manifest('builtin:x')),
    UntrustedText.subprocess('tool missing'), UntrustedText.subprocess(''))

    private static AttemptRecord record(AttemptRecord.Result result, Stop stop) {
        new AttemptRecord(0, result, STARTED, [], ExecutorUsage.none(), JudgeUsage.none(), [], stop)
    }

    // FR5: a stop of the kind the result names is carried as constructed
    def "carries a stop of the kind its result names"() {
        expect:
        record(result, stop).stop() == stop

        where:
        result | stop
        AttemptRecord.Result.DECISION_NEEDED | DECISION
        AttemptRecord.Result.CANNOT_VERIFY | CANNOT_VERIFY
    }

    // FR5, FR10: None is valid on every result — a pass and a quality failure raise none, and a
    //     record read from a tip predating the stop carries none
    def "accepts None on every result"() {
        expect:
        record(result, Stop.none()).stop() == Stop.none()

        where:
        result << AttemptRecord.Result.values().toList()
    }

    // FR5: a stop contradicting the round's result is refused
    def "refuses a stop whose kind contradicts the result"() {
        when:
        record(result, stop)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == "AttemptRecord.stop ${kind} does not match result ${result}"

        where:
        result | stop | kind
        AttemptRecord.Result.PASSED | DECISION | 'DecisionNeeded'
        AttemptRecord.Result.QUALITY_FAILURE | DECISION | 'DecisionNeeded'
        AttemptRecord.Result.CANNOT_VERIFY | DECISION | 'DecisionNeeded'
        AttemptRecord.Result.PASSED | CANNOT_VERIFY | 'CannotVerify'
        AttemptRecord.Result.QUALITY_FAILURE | CANNOT_VERIFY | 'CannotVerify'
        AttemptRecord.Result.DECISION_NEEDED | CANNOT_VERIFY | 'CannotVerify'
    }

    // FR5: the stop is part of the record's value identity
    def "a differing stop makes two records unequal"() {
        expect:
        record(AttemptRecord.Result.DECISION_NEEDED, DECISION) != record(AttemptRecord.Result.DECISION_NEEDED, Stop.none())
    }
}
