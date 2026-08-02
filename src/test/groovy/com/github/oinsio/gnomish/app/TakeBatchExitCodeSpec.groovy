package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * {@link TakeBatchExitCode#aggregate}: design D7's "smallest non-zero per-ref code, else 0" rule,
 * proven against both families the tracker-take spec "Batch take works the list with a summary
 * and one exit code" names by scenario: the legitimate-outcome family (10 and above) winning when
 * no ref hit a tool failure, and the below-10 tool-failure family dominating when one did.
 *
 * <p>FR3, NFR-O2: task 6.3 of add-factory-serve.
 */
class TakeBatchExitCodeSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('build')

    // FR3, D7: every ref delivering exits 0.
    def "exits 0 when every outcome is exit-code 0"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('a', new TakeResult.Delivered(STATE, 'shipped a')),
            new TakeBatchOutcome('b', new TakeResult.Delivered(STATE, 'shipped b')),
        ]

        expect:
        TakeBatchExitCode.aggregate(outcomes) == 0
    }

    // Delta spec scenario "Mixed batch summarized": WHEN take 42 43 44 delivers 42, skips 43 as
    // held by another instance, and parks 44 as an escalation, THEN the aggregate exit code comes
    // from the legitimate-outcome family — here the smallest of {0, 15, 10} is 10.
    def "mixed batch summarized: the aggregate code comes from the legitimate-outcome family"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('42', new TakeResult.Delivered(STATE, 'shipped')),
            new TakeBatchOutcome('43', new TakeResult.Skipped('held by another instance')),
            new TakeBatchOutcome('44', new TakeResult.AwaitingHuman(STATE, ParkReason.ESCALATION, 'needs a human')),
        ]

        expect:
        TakeBatchExitCode.aggregate(outcomes) == 10
    }

    // Delta spec scenario "Tool failure dominates": WHEN one ref fails with a pipeline load
    // failure and the others deliver, THEN the aggregate exit code comes from the below-10 family
    // — the tool failure's code (2, UsageException's family) beats every delivering ref's 0.
    def "tool failure dominates: the aggregate code comes from the below-10 family"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('42', new TakeResult.Delivered(STATE, 'shipped')),
            TakeBatchOutcome.toolFailure('43', new UsageException('cannot resolve ref')),
            new TakeBatchOutcome('44', new TakeResult.Delivered(STATE, 'shipped too')),
        ]

        expect:
        TakeBatchExitCode.aggregate(outcomes) == 2
    }

    // Edge: a tool failure's below-10 code still wins even against a Skipped ref's 15 and an
    // AwaitingHuman ref's 10/11/13 — the arithmetic dominance holds for every legitimate code, not
    // just 0.
    def "a tool failure outranks every legitimate-outcome code, not only 0"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('42', new TakeResult.AwaitingHuman(STATE, ParkReason.CHECKPOINT, 'paused')),
            new TakeBatchOutcome('43', new TakeResult.Skipped('already done')),
            TakeBatchOutcome.toolFailure('44', new IllegalStateException('crashed')),
        ]

        expect:
        TakeBatchExitCode.aggregate(outcomes) == 1
    }

    // Boundary: pins the code < smallestNonZero comparison at a tie — once the running minimum
    // already equals a later ref's own code, that later ref must not overwrite it (or, read the
    // other way, a first-seen non-zero minimum is never displaced by an equal later code), so a
    // shifted boundary (<= instead of <) would still have to reach the identical final value here.
    def "a repeated minimum non-zero code does not change the aggregate"() {
        given:
        def outcomes = [
            new TakeBatchOutcome('42', new TakeResult.AwaitingHuman(STATE, ParkReason.ESCALATION, 'needs a human')),
            new TakeBatchOutcome('43', new TakeResult.Delivered(STATE, 'shipped')),
            new TakeBatchOutcome('44', new TakeResult.AwaitingHuman(STATE, ParkReason.ESCALATION, 'needs a human too')),
        ]

        expect:
        TakeBatchExitCode.aggregate(outcomes) == 10
    }
}
