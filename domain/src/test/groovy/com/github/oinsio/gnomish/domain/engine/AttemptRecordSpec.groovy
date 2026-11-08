package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * AttemptRecord: the recorded metrics of one executed round within the current
 * stage — its {@code round} number, its {@code startedAt} begin instant, the
 * {@code checkResults} it produced and its {@code usage} — one participant entry per
 * executor invocation (design D14 of define-executor-contract). The raw ToolTrace is deliberately NOT held here;
 * it lives outside TaskState, correlated by AttemptKey (design D5). Implements FR13
 * of add-stage-engine; FR15 of add-manual-run (the {@code startedAt} timestamp).
 */
class AttemptRecordSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static CheckResult passResult() {
        new CheckResult(new CheckRef(0, UntrustedText.manifest('command:./gradlew test')), new Verdict.Pass(null, []), Duration.ofMillis(200))
    }

    private static Finding denial() {
        new Finding('egress denied: evil.example.com:443', 'evil.example.com:443', 'kind=connect')
    }

    // FR13, FR15: a record exposes its round, result, startedAt, checkResults and usage
    //     (FR15 of define-executor-contract: one participant list)
    def "exposes round, result, startedAt, checkResults and the participant list as constructed"() {
        given: 'a check result, the executor entry and one judge vote'
        def check = passResult()
        def usage = [
            Participants.transform('agent-cli', new ExecutorUsage(Duration.ofSeconds(5), [], [:])),
            Participants.verdict('agent-cli', 0, Unpriced.of(['m': new TokenUsage(1, 1, 0, 0)]))
        ]

        when: 'a record is created'
        def record = AttemptRecords.of(2, AttemptRecord.Result.PASSED, STARTED, [check], usage, [], Stop.none())

        then: 'each component is exposed exactly as constructed'
        record.round() == 2
        record.result() == AttemptRecord.Result.PASSED
        record.startedAt() == STARTED
        record.checkResults() == [check]
        record.usage() == usage
    }

    // FR15 of define-executor-contract: the participant list is defensively copied and unmodifiable
    def "the participant list is copied and cannot be modified"() {
        given:
        def usage = [
            Participants.transform('agent-cli', ExecutorUsage.none())
        ]
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [], usage, [], Stop.none())

        when:
        usage.clear()
        record.usage().clear()

        then:
        thrown(UnsupportedOperationException)
        record.usage().size() == 1
    }

    // FR13, D5: the five result classifications the engine can set are exposed as an enum
    //     (FR7 of define-executor-contract added BUDGET_EXHAUSTED)
    def "exposes the five result classifications"() {
        expect: 'each classification value exists'
        AttemptRecord.Result.values().toList() == [
            AttemptRecord.Result.PASSED,
            AttemptRecord.Result.QUALITY_FAILURE,
            AttemptRecord.Result.CANNOT_VERIFY,
            AttemptRecord.Result.DECISION_NEEDED,
            AttemptRecord.Result.BUDGET_EXHAUSTED
        ]
    }

    // FR13, D5: the result is exposed exactly as constructed, for each classification
    def "exposes the constructed result classification"() {
        expect: 'the accessor returns the exact classification it was built with'
        AttemptRecords.of(0, result, STARTED, [passResult()], ExecutorUsage.none(), [], Stop.none()).result() ==
        result

        where:
        result << AttemptRecord.Result.values()
    }

    // FR15: startedAt is exposed exactly as constructed — the engine's begin-of-round Clock reading
    def "exposes the constructed startedAt instant"() {
        expect: 'the accessor returns the exact begin instant it was built with'
        AttemptRecords.of(0, AttemptRecord.Result.PASSED, instant, [passResult()], ExecutorUsage.none(),
        [], Stop.none()).startedAt() == instant

        where:
        instant << [
            Instant.EPOCH,
            STARTED,
            Instant.parse('2030-01-01T00:00:00Z')
        ]
    }

    // FR14 of define-executor-contract: the record names its task, its stage and its token as given
    def "exposes its identity — taskId, stage and roundToken — as constructed"() {
        given:
        def token = RoundToken.of('abc123')

        when:
        def record = AttemptRecords.identified('PROJ-4', 'review', token, 2, AttemptRecord.Result.PASSED, STARTED, [],
        ExecutorUsage.none(), [], Stop.none())

        then:
        record.taskId() == 'PROJ-4'
        record.stage() == 'review'
        record.roundToken() == token
        record.round() == 2
    }

    // FR14 of define-executor-contract: the in-place run's record carries no token
    def "accepts a record with no round token"() {
        expect:
        AttemptRecords.identified('PROJ-4', 'review', null, 0, AttemptRecord.Result.PASSED, STARTED, [],
        ExecutorUsage.none(), [], Stop.none()).roundToken() == null
    }

    // FR14 of define-executor-contract: a record never lacks its task or its stage
    def "rejects a #shape #component with the component named"() {
        when:
        AttemptRecords.identified(taskId, stage, null, 0, AttemptRecord.Result.PASSED, STARTED, [],
        ExecutorUsage.none(), [], Stop.none())

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == "AttemptRecord.${component} must not be blank".toString()

        where:
        shape | component | taskId | stage
        'null' | 'taskId' | null | 'build'
        'blank' | 'taskId' | '  ' | 'build'
        'null' | 'stage' | 'T-1' | null
        'blank' | 'stage' | 'T-1' | ' '
    }

    // FR15: startedAt is required non-null — a null begin instant is rejected with the component named
    def "rejects a null startedAt with the component named"() {
        when: 'a record is created with no begin instant'
        AttemptRecords.of(0, AttemptRecord.Result.PASSED, null, [passResult()], ExecutorUsage.none(),
        [], Stop.none())

        then: 'construction fails and the message names the startedAt component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptRecord.startedAt')
    }

    // FR13: a validated round round-trips a specific non-trivial literal
    // (pins requireNonNegative's return against a return-value mutation)
    def "a validated round round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-zero round it was built with'
        AttemptRecords.of(6, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        [], Stop.none()).round() == 6
    }

    // FR13: round is a round sequence number — the base round zero is accepted
    def "accepts round zero"() {
        when: 'a record is created at the base round'
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        [], Stop.none())

        then: 'the zero round is exposed as constructed'
        record.round() == 0
    }

    // FR13: a round number cannot be negative — a negative round is rejected
    def "rejects a negative round with the component named"() {
        when: 'a record is created with a negative round'
        AttemptRecords.of(round, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        [], Stop.none())

        then: 'construction fails and the message names the round component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptRecord.round')

        where:
        round << [-1, -10, -100]
    }

    // FR13: a DecisionNeeded round records no checks — an empty checkResults list is accepted
    def "accepts an empty checkResults list"() {
        when: 'a record is created with no check results'
        def record = AttemptRecords.of(1, AttemptRecord.Result.DECISION_NEEDED, STARTED, [], ExecutorUsage.none(),
        [], Stop.none())

        then: 'the empty list is exposed'
        record.checkResults().isEmpty()
    }

    // FR13: checkResults is defensively copied — mutating the source does not leak in
    def "defensively copies checkResults so source mutation does not leak"() {
        given: 'a mutable source list'
        def source = [passResult()]

        when: 'a record is created and the source is then mutated'
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, source, ExecutorUsage.none(),
                [], Stop.none())
        source.add(passResult())

        then: 'the record keeps the snapshot taken at construction'
        record.checkResults().size() == 1
    }

    // FR13: checkResults is unmodifiable once constructed
    def "exposes checkResults as unmodifiable"() {
        given: 'a record'
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        [], Stop.none())

        when: 'a caller tries to mutate the exposed list'
        record.checkResults().add(passResult())

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR2 of fix-denial-report-attachment: denials are defensively copied — the guard read that
    //     produced them is the environment's, and a later mutation of it must not rewrite a
    //     committed attempt's record. PIT's record filter emits no mutant for the compact
    //     constructor, so this invariant is only held by a spec.
    def "defensively copies denials so source mutation does not leak"() {
        given: 'a mutable source list'
        def source = [denial()]

        when: 'a record is created and the source is then mutated'
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        source, Stop.none())
        source.add(denial())

        then: 'the record keeps the snapshot taken at construction'
        record.denials().size() == 1
    }

    // FR2 of fix-denial-report-attachment: denials is unmodifiable once constructed
    def "exposes denials as unmodifiable"() {
        given: 'a record carrying one denial'
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        [denial()], Stop.none())

        when: 'a caller tries to mutate the exposed list'
        record.denials().add(denial())

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR13: a round whose executor reported nothing is valid (interactive/unreported case): its one
    //     entry is ABSENT, never missing (FR15 of define-executor-contract)
    def "accepts an executor that reported nothing as one absent entry"() {
        when: 'a record is created with the empty usage sentinel'
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [passResult()], ExecutorUsage.none(),
        [], Stop.none())

        then: 'the round carries the executor as an absent entry'
        record.usage()*.provenance() == [UsageProvenance.ABSENT]
        record.usage()[0].byModel().isEmpty()
    }

    // FR13, FR15: AttemptRecord is inert value data compared by content
    def "is value-equal by content"() {
        given: 'shared components'
        def check = passResult()
        def executorUsage = ExecutorUsage.none()

        expect: 'two records built from equal components are equal'
        AttemptRecords.of(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, [], Stop.none()) ==
        AttemptRecords.of(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, [], Stop.none())

        and: 'a differing round makes them unequal'
        AttemptRecords.of(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, [], Stop.none()) !=
        AttemptRecords.of(2, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, [], Stop.none())

        and: 'a differing result classification makes them unequal (result is part of value identity)'
        AttemptRecords.of(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, [], Stop.none()) !=
        AttemptRecords.of(1, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [check], executorUsage, [], Stop.none())

        and: 'a differing startedAt makes them unequal (the begin instant is part of value identity)'
        AttemptRecords.of(1, AttemptRecord.Result.PASSED, STARTED, [check], executorUsage, [], Stop.none()) !=
        AttemptRecords.of(1, AttemptRecord.Result.PASSED, Instant.EPOCH, [check], executorUsage, [], Stop.none())
    }
}
