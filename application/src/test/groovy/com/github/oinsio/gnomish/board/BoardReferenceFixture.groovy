package com.github.oinsio.gnomish.board

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.BackoffPolicy
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Duration
import java.time.Instant

/**
 * The deterministic sample {@link BoardModel} shared by the reference-anchor JSON spec and the
 * text/JSON agreement spec (task 4.3, mirroring {@code StatusReportJsonMapperSpec#referenceReport}):
 * fixed {@link Instant} values throughout, covering every Ready eligibility reason (in-backoff
 * with a deadline, finished, WIP-held) plus a returned row, a Working row with a live claim
 * marker and one with an absent marker, and one AwaitingHuman row per park reason. Used both to
 * (re)generate {@code board-v1.reference.json} and to keep both specs' spot-checked facts
 * traceable to a single shared model.
 *
 * <p>The returned row doubles as the eligible-now example: {@link
 * com.github.oinsio.gnomish.board.EligibilityPolicy}'s WIP gate is a single global condition
 * ({@code openFrontCount >= wipLimit}) applied uniformly to every non-returned, non-backed-off,
 * non-finished row, so a plain fresh row cannot be eligible-now in the same snapshot where
 * another plain fresh row is WIP-held — a returned row is the only row that can be both eligible
 * and demonstrate a distinct annotation (returned) under the same WIP-held-triggering counts.
 *
 * <p>Implements FR6, UX4, M1 of add-board-command.
 */
final class BoardReferenceFixture {

    static final Instant GENERATED_AT = Instant.parse('2026-08-05T09:00:00Z')
    static final int WIP_LIMIT = 3
    static final int OPEN_FRONT_COUNT = 5

    private static final Duration BASE = BackoffPolicy.DEFAULT_BASE
    private static final Duration CAP = BackoffPolicy.DEFAULT_CAP
    private static final Instant BACKOFF_LAST_ABORT_AT = GENERATED_AT - Duration.ofMinutes(1)

    static final Instant BACKOFF_DEADLINE = BACKOFF_LAST_ABORT_AT + BackoffPolicy.delay(1, BASE, CAP)
    static final Instant WORKING_CLAIM_UPDATED_AT = GENERATED_AT - Duration.ofMinutes(3)

    private BoardReferenceFixture() {}

    /**
     * Builds the deterministic reference {@link BoardModel}: four Ready rows (in-backoff,
     * finished, WIP-held, and a returned row that is also eligible-now), two Working rows (one
     * fresh claim marker, one absent), and one AwaitingHuman row per {@link ParkReason}, all
     * fixed against {@link #GENERATED_AT}.
     */
    static BoardModel referenceModel() {
        def ready = [
            new ReadyTask(new TaskRef('github:g/r#1'), new AbortFacts(1, BACKOFF_LAST_ABORT_AT), false, false, 'Fix flaky OrderServiceSpec'),
            new ReadyTask(new TaskRef('github:g/r#2'), AbortFacts.none(), false, true, 'Old reopened task'),
            new ReadyTask(new TaskRef('github:g/r#3'), AbortFacts.none(), false, false, 'Add new feature flag'),
            new ReadyTask(new TaskRef('github:g/r#4'), AbortFacts.none(), true, false, 'Returned after park')
        ]

        def open = [
            new OpenTask(new TaskRef('github:g/w#1'), new TrackerTaskState.Working('factory-a-1b2c'),
            new ClaimVersion('marker-1', WORKING_CLAIM_UPDATED_AT, new ClaimEpoch(1)), 'Refactor retry module'),
            new OpenTask(new TaskRef('github:g/w#2'), new TrackerTaskState.Working('factory-b-9f00'), null, 'Update operator docs'),
            new OpenTask(new TaskRef('github:h/1'), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), null, 'Needs operator decision'),
            new OpenTask(new TaskRef('github:h/2'), new TrackerTaskState.AwaitingHuman(ParkReason.INFRA), null, 'Environment broken'),
            new OpenTask(new TaskRef('github:h/3'), new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT), null, 'Checkpoint pause')
        ]

        return BoardModel.build(ready, open, true, GENERATED_AT, BASE, CAP, GENERATED_AT, OPEN_FRONT_COUNT, WIP_LIMIT)
    }
}
