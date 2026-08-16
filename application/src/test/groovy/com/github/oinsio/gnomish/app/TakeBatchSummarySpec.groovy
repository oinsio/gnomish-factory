package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * {@link TakeBatchSummary#render}: the machine-findable, one-line checklist a batch {@code take}
 * run closes with (FR3, NFR-O2, UX3 of add-factory-serve) — "a batch run reads like a checklist
 * afterwards: every ref, its outcome" (UX3).
 *
 * <p>Implements FR3, NFR-O2, UX3 of add-factory-serve.
 */
class TakeBatchSummarySpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('build')

    // Delta spec scenario "Mixed batch summarized": every one of the three outcomes is named in
    // the summary, in the CLI order they were given.
    def "names every ref and its outcome, in order"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('42', new TakeResult.Delivered(STATE, 'shipped it')),
            new TakeBatchOutcome('43', new TakeResult.Skipped('held by another instance')),
            new TakeBatchOutcome('44', new TakeResult.AwaitingHuman(STATE, ParkReason.ESCALATION, 'needs a human')),
        ]

        expect:
        TakeBatchSummary.render(outcomes) == 'batch take: 3 ref(s) — ' +
                '42 -> delivered: shipped it, ' +
                '43 -> skipped: held by another instance, ' +
                '44 -> parked (ESCALATION): needs a human'
    }

    // Tool failures are named in the same checklist as ordinary outcomes.
    def "names a tool-failure outcome alongside ordinary outcomes"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('42', new TakeResult.Delivered(STATE, 'shipped it')),
            TakeBatchOutcome.toolFailure('43', new UsageException('cannot resolve ref')),
        ]

        expect:
        TakeBatchSummary.render(outcomes) == 'batch take: 2 ref(s) — ' +
                '42 -> delivered: shipped it, ' +
                '43 -> tool failure (exit 2): cannot resolve ref'
    }
}
