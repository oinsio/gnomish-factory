package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.board.AwaitingHumanRow
import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.EligibilityReason
import com.github.oinsio.gnomish.board.ReadyRow
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.board.WorkingRow
import java.time.Instant
import spock.lang.Specification

/**
 * BoardTextRenderer: the plain-text rendering of a BoardModel's three columns (task 4.1),
 * covering the tracker-board spec.md scenarios that pertain to text output: eligibility
 * annotations (backoff deadline, finished, WIP-held), summary reconciliation, the
 * returned/fresh distinction, truncation, Working-row claim freshness (age and unknown), and
 * AwaitingHuman park reasons.
 *
 * <p>Implements FR2, FR3, FR4, FR5, UX1 of add-board-command.
 */
class BoardTextRendererSpec extends Specification {

    private static final Instant GENERATED_AT = Instant.parse('2026-08-05T09:00:00Z')

    private final BoardTextRenderer renderer = new BoardTextRenderer()

    private static BoardModel model(
            List<ReadyRow> readyRows = [],
            List<WorkingRow> workingRows = [],
            List<AwaitingHumanRow> awaitingHumanRows = [],
            boolean truncated = false) {
        new BoardModel(readyRows, workingRows, awaitingHumanRows, ReadySummary.tally(readyRows), truncated, GENERATED_AT)
    }

    // UX1: three columns, in Ready / Working / AwaitingHuman order
    def "renders the three columns in Ready, Working, AwaitingHuman order"() {
        given:
        def board = model(
                [
                    new ReadyRow(new TaskRef('r-1'), 'Ready task', false, null)
                ],
                [
                    new WorkingRow(new TaskRef('w-1'), 'Working task', 'holder-a', null)
                ],
                [
                    new AwaitingHumanRow(new TaskRef('a-1'), 'Parked task', ParkReason.ESCALATION)
                ])

        when:
        def text = renderer.render(board)

        then:
        def readyIndex = text.indexOf('Ready')
        def workingIndex = text.indexOf('Working')
        def awaitingIndex = text.indexOf('AwaitingHuman')
        readyIndex >= 0
        readyIndex < workingIndex
        workingIndex < awaitingIndex
    }

    // FR2, spec scenario "Backed-off task is annotated with its deadline"
    def "annotates a backed-off Ready row with its deadline, leaving eligible rows unannotated"() {
        given:
        def deadline = Instant.parse('2026-08-05T14:02:00Z')
        def board = model([
            new ReadyRow(new TaskRef('r-backoff'), 'Backed off', false, new EligibilityReason.InBackoff(deadline)),
            new ReadyRow(new TaskRef('r-eligible'), 'Eligible', false, null)
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('r-backoff - Backed off — in backoff until 2026-08-05T14:02:00Z')
        text.contains('r-eligible - Eligible')
        !text.readLines().find { it.contains('r-eligible') }.contains('—')
    }

    // FR2, spec scenario "Finished task is not counted eligible"
    def "annotates a finished Ready row as finished"() {
        given:
        def board = model([
            new ReadyRow(new TaskRef('r-1'), 'Reopened task', false, new EligibilityReason.Finished())
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('r-1 - Reopened task — finished')
    }

    // FR2, spec scenario "Fresh task is WIP-held when the front is full"
    def "annotates a WIP-held Ready row"() {
        given:
        def board = model([
            new ReadyRow(new TaskRef('r-1'), 'Fresh task', false, new EligibilityReason.WipHeld())
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('r-1 - Fresh task — WIP-held')
    }

    // FR3, spec scenario "Summary counts reconcile"
    def "renders the Ready summary line reconciling queued, eligible, and each ineligible reason"() {
        given:
        def deadline = Instant.parse('2026-08-05T09:14:00Z')
        def readyRows = [
            new ReadyRow(new TaskRef('r-1'), 't1', false, null),
            new ReadyRow(new TaskRef('r-2'), 't2', false, null),
            new ReadyRow(new TaskRef('r-3'), 't3', false, null),
            new ReadyRow(new TaskRef('r-4'), 't4', false, new EligibilityReason.InBackoff(deadline)),
            new ReadyRow(new TaskRef('r-5'), 't5', false, new EligibilityReason.InBackoff(deadline)),
            new ReadyRow(new TaskRef('r-6'), 't6', false, new EligibilityReason.Finished()),
            new ReadyRow(new TaskRef('r-7'), 't7', false, new EligibilityReason.WipHeld())
        ]
        def board = model(readyRows)

        when:
        def text = renderer.render(board)

        then:
        text.contains('7 queued, 3 eligible, 2 in backoff, 1 finished, 1 WIP-held')
    }

    // spec scenario "Returned task is distinguished"
    def "marks a returned Ready row as returned"() {
        given:
        def board = model([
            new ReadyRow(new TaskRef('r-returned'), 'Returned task', true, null),
            new ReadyRow(new TaskRef('r-fresh'), 'Fresh task', false, null)
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('r-returned - Returned task (returned)')
        !text.contains('r-fresh - Fresh task (returned)')
    }

    // FR3, spec scenario "Truncated window is honest"
    def "notes truncation when the ready window was capped"() {
        given:
        def board = model([
            new ReadyRow(new TaskRef('r-1'), 't1', false, null)
        ], [], [], true)

        when:
        def text = renderer.render(board)

        then:
        text.contains('truncated')
    }

    def "does not mention truncation when the window is not capped"() {
        given:
        def board = model([
            new ReadyRow(new TaskRef('r-1'), 't1', false, null)
        ], [], [], false)

        when:
        def text = renderer.render(board)

        then:
        !text.contains('truncated')
    }

    // FR4, spec scenario "Working rows show holder and claim age"
    def "renders a Working row's holder and claim age, with no staleness verdict"() {
        given:
        def claimVersion = new ClaimVersion('marker-1', GENERATED_AT.minusSeconds(180))
        def board = model([], [
            new WorkingRow(new TaskRef('w-1'), 'Working task', 'factory-a-1b2c', claimVersion)
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('w-1 - Working task (holder=factory-a-1b2c, updated 3m ago)')
        !text.toLowerCase().contains('stale')
        !text.toLowerCase().contains('healthy')
    }

    // FR4, spec scenario "Working row with a missing claim marker"
    def "renders a Working row's freshness as unknown when the claim marker is absent"() {
        given:
        def board = model([], [
            new WorkingRow(new TaskRef('w-1'), 'Working task', 'factory-a-1b2c', null)
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('w-1 - Working task (holder=factory-a-1b2c, freshness unknown)')
    }

    // FR5, spec scenario "Park reasons are spelled out"
    def "renders AwaitingHuman rows with their park reason"() {
        given:
        def board = model([], [], [
            new AwaitingHumanRow(new TaskRef('a-1'), 'Escalated task', ParkReason.ESCALATION),
            new AwaitingHumanRow(new TaskRef('a-2'), 'Checkpointed task', ParkReason.CHECKPOINT),
            new AwaitingHumanRow(new TaskRef('a-3'), 'Infra task', ParkReason.INFRA)
        ])

        when:
        def text = renderer.render(board)

        then:
        text.contains('a-1 - Escalated task (reason=escalation)')
        text.contains('a-2 - Checkpointed task (reason=checkpoint)')
        text.contains('a-3 - Infra task (reason=infra)')
    }
}
