package com.github.oinsio.gnomish.board

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant
import spock.lang.Specification

/**
 * BoardModel: the board's single immutable model, built from exactly one
 * listReady result and one listOpen result, three columns preserving
 * adapter list order (design D5). Implements FR2-FR5, NFR-P1 of
 * add-board-command.
 */
class BoardModelSpec extends Specification {

    private static final Instant GENERATED_AT = Instant.parse('2026-08-05T09:00:00Z')

    private static ReadyTask ready(String id, boolean returned = false) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), returned, false, "title-$id")
    }

    private static OpenTask working(String id, String holder, ClaimVersion version = null) {
        new OpenTask(new TaskRef(id), new TrackerTaskState.Working(holder), version, "title-$id")
    }

    private static OpenTask awaitingHuman(String id, ParkReason reason) {
        new OpenTask(new TaskRef(id), new TrackerTaskState.AwaitingHuman(reason), null, "title-$id")
    }

    // FR2, FR3: a model is built from a listReady result and a listOpen result, with the queued count reflecting the ready window
    def "builds a model from ready and open results with a queued count matching the ready window"() {
        given: 'two ready tasks and no open tasks'
        def readyTasks = [
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ]

        when: 'the model is built'
        def model = BoardModel.build(readyTasks, [], false, GENERATED_AT)

        then: 'the Ready column carries both rows and the summary counts the fetched window'
        model.readyRows().size() == 2
        model.summary() == new ReadySummary(2, 2, 0, 0, 0)
        model.workingRows().isEmpty()
        model.awaitingHumanRows().isEmpty()
    }

    // FR2: the Ready column preserves listReady's original order
    def "preserves the ready list's order in the Ready column"() {
        given: 'ready tasks in a specific order'
        def readyTasks = [
            ready('github:o/r#3'),
            ready('github:o/r#1'),
            ready('github:o/r#2')
        ]

        when: 'the model is built'
        def model = BoardModel.build(readyTasks, [], false, GENERATED_AT)

        then: 'the row order matches the input order exactly'
        model.readyRows()*.ref()*.id() == [
            'github:o/r#3',
            'github:o/r#1',
            'github:o/r#2'
        ]
    }

    // FR4: the Working column preserves listOpen's original order, routing only Working entries
    def "preserves the open list's order in the Working column and routes only Working entries"() {
        given: 'an open list mixing Working and AwaitingHuman entries in a specific order'
        def openTasks = [
            working('github:o/r#5', 'factory-b'),
            awaitingHuman('github:o/r#4', ParkReason.ESCALATION),
            working('github:o/r#1', 'factory-a')
        ]

        when: 'the model is built'
        def model = BoardModel.build([], openTasks, false, GENERATED_AT)

        then: 'the Working column contains only Working entries, in listOpen order'
        model.workingRows()*.ref()*.id() == [
            'github:o/r#5',
            'github:o/r#1'
        ]
        model.workingRows()*.holder() == ['factory-b', 'factory-a']
    }

    // FR5: the AwaitingHuman column preserves listOpen's original order, routing only AwaitingHuman entries
    def "preserves the open list's order in the AwaitingHuman column and routes only AwaitingHuman entries"() {
        given: 'an open list mixing Working and AwaitingHuman entries in a specific order'
        def openTasks = [
            awaitingHuman('github:o/r#7', ParkReason.CHECKPOINT),
            working('github:o/r#2', 'factory-a'),
            awaitingHuman('github:o/r#3', ParkReason.INFRA)
        ]

        when: 'the model is built'
        def model = BoardModel.build([], openTasks, false, GENERATED_AT)

        then: 'the AwaitingHuman column contains only AwaitingHuman entries, in listOpen order, with reasons'
        model.awaitingHumanRows()*.ref()*.id() == [
            'github:o/r#7',
            'github:o/r#3'
        ]
        model.awaitingHumanRows()*.reason() == [
            ParkReason.CHECKPOINT,
            ParkReason.INFRA
        ]
    }

    // FR4: a Working row carries the holder and the claim version through unchanged, including a null (missing marker) version
    def "carries holder and claim version through on a Working row"() {
        given: 'a Working task with a live claim version'
        def version = new ClaimVersion('marker-1', Instant.parse('2026-08-05T08:30:00Z'), new ClaimEpoch(1))
        def openTasks = [
            working('github:o/r#1', 'factory-a', version)
        ]

        when: 'the model is built'
        def model = BoardModel.build([], openTasks, false, GENERATED_AT)

        then: 'the row carries the holder and claim version'
        model.workingRows()[0].holder() == 'factory-a'
        model.workingRows()[0].claimVersion() == version

        when: 'a Working task carries no live claim marker'
        def modelMissingMarker = BoardModel.build([], [
            working('github:o/r#2', 'factory-b')
        ], false, GENERATED_AT)

        then: 'the row carries a null claim version'
        modelMissingMarker.workingRows()[0].claimVersion() == null
    }

    // FR2: a ready row distinguishes returned from fresh tasks
    def "distinguishes returned from fresh ready tasks"() {
        given: 'one returned and one fresh ready task'
        def readyTasks = [
            ready('github:o/r#1', true),
            ready('github:o/r#2', false)
        ]

        when: 'the model is built'
        def model = BoardModel.build(readyTasks, [], false, GENERATED_AT)

        then: 'the returned distinction is preserved per row'
        model.readyRows()[0].returned()
        !model.readyRows()[1].returned()
    }

    // FR2: the four-argument build overload defaults every ready row to eligible (no eligibility params supplied)
    def "defaults every ready row to eligible when built without eligibility parameters"() {
        given: 'a ready task'
        def readyTasks = [ready('github:o/r#1')]

        when: 'the model is built'
        def model = BoardModel.build(readyTasks, [], false, GENERATED_AT)

        then: 'the row carries no eligibility reason yet'
        model.readyRows()[0].eligibilityReason() == null
    }

    // FR6, NFR-O1: truncated and generatedAt are passed through unchanged from the caller
    def "passes truncated and generatedAt through unchanged"() {
        when: 'a model is built with an explicit truncated flag and generation instant'
        def model = BoardModel.build([ready('github:o/r#1')], [], true, GENERATED_AT)

        then: 'both fields reflect the caller-supplied values exactly'
        model.truncated()
        model.generatedAt() == GENERATED_AT
    }

    // NFR-P1: the model is built from exactly the two supplied lists, with no other data source
    def "builds an empty model from empty ready and open results"() {
        when: 'the model is built from empty inputs'
        def model = BoardModel.build([], [], false, GENERATED_AT)

        then: 'all three columns are empty and the summary counts zero'
        model.readyRows().isEmpty()
        model.workingRows().isEmpty()
        model.awaitingHumanRows().isEmpty()
        model.summary() == new ReadySummary(0, 0, 0, 0, 0)
    }

    // FR4, FR5: an OpenTask carrying a state listOpen must never return (Ready/Finished/Gone) is
    // rejected defensively rather than silently dropped (the switch's `default -> throw` guard).
    def "rejects a listOpen entry carrying an out-of-contract #state state"() {
        given: 'an open task in a state the listOpen contract forbids'
        def openTasks = [
            new OpenTask(new TaskRef('github:o/r#9'), state, null, 'title')
        ]

        when: 'the model is built'
        BoardModel.build([], openTasks, false, GENERATED_AT)

        then: 'the build fails loudly, naming the offending ref and its state'
        def e = thrown(IllegalStateException)
        e.message.contains('listOpen contract violation')
        e.message.contains('github:o/r#9')

        where:
        state << [
            new TrackerTaskState.Ready(),
            new TrackerTaskState.Finished(),
            new TrackerTaskState.Gone()
        ]
    }

    // FR2-FR5: BoardModel rows are exposed as unmodifiable, defensively-copied lists
    def "exposes columns as unmodifiable"() {
        given: 'a built model'
        def model = BoardModel.build([ready('github:o/r#1')], [], false, GENERATED_AT)

        when: 'a caller tries to mutate the exposed Ready column'
        model.readyRows().add(new ReadyRow(new TaskRef('github:o/r#2'), 'x', false, null))

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }
}
