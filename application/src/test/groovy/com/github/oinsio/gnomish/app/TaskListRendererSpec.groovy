package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.TaskListRow
import com.github.oinsio.gnomish.domain.branch.BranchShape
import spock.lang.Specification

/**
 * FR13 of add-git-workflow: {@code gnomish status}' list mode renders a plain-text table (default)
 * or a JSON array ({@code --json}) of {@link TaskListRow}s, with "in progress" standing in for a
 * {@code null} outcome and "-" standing in for a {@code null} stage.
 */
class TaskListRendererSpec extends Specification {

    def renderer = new TaskListRenderer()

    def "renderText() renders 'no tasks found' for an empty list"() {
        expect:
        renderer.renderText([]) == 'no tasks found'
    }

    // PIT NegateConditionalsMutator on the outcome==null ternary: a null outcome must render
    // "in progress", and a non-null outcome must render its own literal value, not the fallback.
    def "renderText() renders 'in progress' for a null outcome and the outcome's own text otherwise"() {
        given:
        def rows = [
            new TaskListRow('PROJ-1', 'build', 0, null, new BranchShape.InProgress()),
            new TaskListRow('PROJ-2', 'build', 1, 'completed', new BranchShape.CompletedUncleaned()),
        ]

        when:
        def output = renderer.renderText(rows)
        def lines = output.readLines()

        then:
        lines[1].contains('in progress')
        !lines[1].contains('completed')
        lines[2].contains('completed')
        !lines[2].contains('in progress')
    }

    def "renderText() renders '-' for a null stage (pipeline end)"() {
        given:
        def rows = [
            new TaskListRow('PROJ-3', null, 2, 'completed', new BranchShape.CompletedUncleaned())
        ]

        when:
        def output = renderer.renderText(rows)

        then:
        output.readLines()[1].contains('-')
    }

    def "renderJson() renders one object per row with nullable fields preserved"() {
        given:
        def rows = [
            new TaskListRow('PROJ-4', null, 3, null, new BranchShape.InProgress())
        ]

        when:
        def json = renderer.renderJson(rows)

        then:
        json.contains('"taskId" : "PROJ-4"')
        json.contains('"stage" : null')
        json.contains('"attemptsUsed" : 3')
        json.contains('"outcome" : null')
        json.contains('"shape" : "InProgress"')
        json.contains('"diagnosis" : null')
    }

    // FR16 of harden-task-branch-contract: every branch is one row, whatever its shape.
    def "FR16: a mixed-shape listing renders one row per branch, each naming its shape"() {
        given:
        def rows = [
            new TaskListRow('DELIVERED-1', null, 0, null, new BranchShape.Delivered()),
            new TaskListRow('FRESH-1', 'build', 0, null, new BranchShape.Created()),
            new TaskListRow('FLIGHT-1', 'build', 1, null, new BranchShape.InProgress()),
            new TaskListRow('PARKED-1', 'build', 2, 'escalated', new BranchShape.Parked()),
        ]

        when:
        def lines = renderer.renderText(rows).readLines()

        then: 'four rows plus the header, each naming its own shape'
        lines.size() == 5
        lines[1].contains('Delivered')
        lines[2].contains('Created')
        lines[3].contains('InProgress')
        lines[4].contains('Parked')

        and: 'a delivered branch has no outcome to report and is not called in progress'
        !lines[1].contains('in progress')
    }

    def "FR16: a bad branch renders as one diagnostic row naming its shape and diagnosis"() {
        given:
        def rows = [
            new TaskListRow('HEALTHY-1', 'build', 0, null, new BranchShape.InProgress()),
            new TaskListRow('gnomish-BAD', null, 0, null, new BranchShape.Corrupt('state.json: unexpected token')),
        ]

        when:
        def text = renderer.renderText(rows)
        def json = renderer.renderJson(rows)

        then: 'the healthy row is untouched and the bad branch is one row carrying its diagnosis'
        text.readLines().size() == 3
        text.readLines()[2].contains('Corrupt')
        text.readLines()[2].contains('state.json: unexpected token')

        and: 'the JSON row carries the shape and the diagnosis as their own fields'
        json.contains('"shape" : "Corrupt"')
        json.contains('"diagnosis" : "corrupt content (state.json: unexpected token)"')
    }

    def "FR16: an unsupported-version row names the observed and supported versions"() {
        given:
        def rows = [
            new TaskListRow('gnomish-V2', null, 0, null, new BranchShape.UnsupportedVersion('state.json', 2, 1))
        ]

        when:
        def text = renderer.renderText(rows)

        then:
        text.readLines()[1].contains('UnsupportedVersion')
        text.readLines()[1].contains('state.json declaring version 2 where this factory supports 1')
    }
}
