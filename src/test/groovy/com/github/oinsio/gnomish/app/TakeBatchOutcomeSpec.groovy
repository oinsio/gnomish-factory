package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * {@link TakeBatchOutcome}: the invariant that exactly one of {@code result}/{@code toolFailure}
 * is set, and the {@code exitCode()}/{@code describe()} views task 6.3's summary and aggregate
 * exit code are built from.
 *
 * <p>FR3, NFR-O2, D7 of add-factory-serve.
 */
class TakeBatchOutcomeSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('build')

    def "an ordinary result carries its own exit code and description"() {
        given:
        def outcome = new TakeBatchOutcome('42', new TakeResult.Delivered(STATE, 'shipped it'))

        expect:
        outcome.result() != null
        outcome.toolFailure() == null
        outcome.exitCode() == 0
        outcome.describe() == 'delivered: shipped it'
    }

    def "a tool failure carries the RunExitCodeMapper family's code and its message"() {
        given:
        def outcome = TakeBatchOutcome.toolFailure('42', new UsageException('unknown tracker type'))

        expect:
        outcome.result() == null
        outcome.toolFailure() != null
        outcome.exitCode() == 2
        outcome.describe().contains('unknown tracker type')
        outcome.describe().contains('exit 2')
    }

    def "a tool failure with no message falls back to the exception's class name"() {
        given:
        def outcome = TakeBatchOutcome.toolFailure('42', new IllegalStateException())

        expect:
        outcome.describe().contains('IllegalStateException')
    }

    def "rejects both result and toolFailure being set"() {
        when:
        new TakeBatchOutcome('42', new TakeResult.Delivered(STATE, 'shipped'),
                new TakeBatchOutcome.ToolFailure(2, 'bad'))

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects neither result nor toolFailure being set"() {
        when:
        new TakeBatchOutcome('42', null, null)

        then:
        thrown(IllegalArgumentException)
    }
}
