package com.github.oinsio.gnomish.domain.engine

import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * AttemptRecord: the recorded metrics of one executed round within the current
 * stage — its {@code round} number, its {@code startedAt} begin instant, the
 * {@code checkResults} it produced, the round's {@code executorUsage} and its
 * {@code judgeUsage} (design D4). The raw ToolTrace is deliberately NOT held here;
 * it lives outside TaskState, correlated by AttemptKey (design D5). Implements FR13
 * of add-stage-engine; FR15 of add-manual-run (the {@code startedAt} timestamp).
 */
class AttemptRecordSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static CheckResult passResult() {
        new CheckResult(new CheckRef(0, 'command:./gradlew test'), new Verdict.Pass(), Duration.ofMillis(200))
    }

    // FR13, FR15: a record exposes its round, result, startedAt, checkResults, executorUsage and judgeUsage
    def "exposes round, result, startedAt, checkResults, executorUsage and judgeUsage as constructed"() {
        given: 'a check result, an executor usage and a judge usage'
        def check = passResult()
        def executorUsage = new ExecutorUsage(Duration.ofSeconds(5), [], [:])
        def judgeUsage = new JudgeUsage([])

        when: 'a record is created'
        def record = new AttemptRecord(2, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage)

        then: 'each component is exposed exactly as constructed'
        record.round() == 2
        record.result() == AttemptRecord.Result.PASSED
        record.startedAt() == STARTED
        record.checkResults() == [check]
        record.executorUsage() == executorUsage
        record.judgeUsage() == judgeUsage
    }

    // FR13, D5: the four result classifications the engine can set are exposed as an enum
    def "exposes the four result classifications"() {
        expect: 'each classification value exists'
        AttemptRecord.Result.values().toList() == [
            AttemptRecord.Result.PASSED,
            AttemptRecord.Result.QUALITY_FAILURE,
            AttemptRecord.Result.CANNOT_VERIFY,
            AttemptRecord.Result.DECISION_NEEDED
        ]
    }

    // FR13, D5: the result is exposed exactly as constructed, for each classification
    def "exposes the constructed result classification"() {
        expect: 'the accessor returns the exact classification it was built with'
        new AttemptRecord(0, result, STARTED, [passResult()], ExecutorUsage.none(), JudgeUsage.none()).result() ==
        result

        where:
        result << AttemptRecord.Result.values()
    }

    // FR15: startedAt is exposed exactly as constructed — the engine's begin-of-round Clock reading
    def "exposes the constructed startedAt instant"() {
        expect: 'the accessor returns the exact begin instant it was built with'
        new AttemptRecord(0, AttemptRecord.Result.PASSED, instant, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none()).startedAt() == instant

        where:
        instant << [
            Instant.EPOCH,
            STARTED,
            Instant.parse('2030-01-01T00:00:00Z')
        ]
    }

    // FR15: startedAt is required non-null — a null begin instant is rejected with the component named
    def "rejects a null startedAt with the component named"() {
        when: 'a record is created with no begin instant'
        new AttemptRecord(0, AttemptRecord.Result.PASSED, null, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none())

        then: 'construction fails and the message names the startedAt component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptRecord.startedAt')
    }

    // FR13: a validated round round-trips a specific non-trivial literal
    // (pins requireNonNegative's return against a return-value mutation)
    def "a validated round round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-zero round it was built with'
        new AttemptRecord(6, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none()).round() == 6
    }

    // FR13: round is a round sequence number — the base round zero is accepted
    def "accepts round zero"() {
        when: 'a record is created at the base round'
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none())

        then: 'the zero round is exposed as constructed'
        record.round() == 0
    }

    // FR13: a round number cannot be negative — a negative round is rejected
    def "rejects a negative round with the component named"() {
        when: 'a record is created with a negative round'
        new AttemptRecord(round, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none())

        then: 'construction fails and the message names the round component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptRecord.round')

        where:
        round << [-1, -10, -100]
    }

    // FR13: a DecisionNeeded round records no checks — an empty checkResults list is accepted
    def "accepts an empty checkResults list"() {
        when: 'a record is created with no check results'
        def record = new AttemptRecord(1, AttemptRecord.Result.DECISION_NEEDED, STARTED, [], ExecutorUsage.none(),
        JudgeUsage.none())

        then: 'the empty list is exposed'
        record.checkResults().isEmpty()
    }

    // FR13: checkResults is defensively copied — mutating the source does not leak in
    def "defensively copies checkResults so source mutation does not leak"() {
        given: 'a mutable source list'
        def source = [passResult()]

        when: 'a record is created and the source is then mutated'
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, source, ExecutorUsage.none(),
                JudgeUsage.none())
        source.add(passResult())

        then: 'the record keeps the snapshot taken at construction'
        record.checkResults().size() == 1
    }

    // FR13: checkResults is unmodifiable once constructed
    def "exposes checkResults as unmodifiable"() {
        given: 'a record'
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none())

        when: 'a caller tries to mutate the exposed list'
        record.checkResults().add(passResult())

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR13: a round with no reportable executor or judge usage is valid (interactive/unreported case)
    def "accepts ExecutorUsage.none and JudgeUsage.none"() {
        when: 'a record is created with the empty usage sentinels'
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        JudgeUsage.none())

        then: 'the sentinel usages are exposed'
        record.executorUsage() == ExecutorUsage.none()
        record.judgeUsage() == JudgeUsage.none()
    }

    // FR13, FR15: AttemptRecord is inert value data compared by content
    def "is value-equal by content"() {
        given: 'shared components'
        def check = passResult()
        def executorUsage = ExecutorUsage.none()
        def judgeUsage = JudgeUsage.none()

        expect: 'two records built from equal components are equal'
        new AttemptRecord(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage) ==
        new AttemptRecord(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage)

        and: 'a differing round makes them unequal'
        new AttemptRecord(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage) !=
        new AttemptRecord(2, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage)

        and: 'a differing result classification makes them unequal (result is part of value identity)'
        new AttemptRecord(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage) !=
        new AttemptRecord(1, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [check], executorUsage, judgeUsage)

        and: 'a differing startedAt makes them unequal (the begin instant is part of value identity)'
        new AttemptRecord(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, judgeUsage) !=
        new AttemptRecord(1, AttemptRecord.Result.PASSED, Instant.EPOCH, [check], executorUsage, judgeUsage)
    }
}
