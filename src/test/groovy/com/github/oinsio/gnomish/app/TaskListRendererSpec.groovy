package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskListRow
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
            new TaskListRow('PROJ-1', 'build', 0, null),
            new TaskListRow('PROJ-2', 'build', 1, 'completed'),
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
            new TaskListRow('PROJ-3', null, 2, 'completed')
        ]

        when:
        def output = renderer.renderText(rows)

        then:
        output.readLines()[1].contains('-')
    }

    def "renderJson() renders one object per row with nullable fields preserved"() {
        given:
        def rows = [
            new TaskListRow('PROJ-4', null, 3, null)
        ]

        when:
        def json = renderer.renderJson(rows)

        then:
        json.contains('"taskId" : "PROJ-4"')
        json.contains('"stage" : null')
        json.contains('"attemptsUsed" : 3')
        json.contains('"outcome" : null')
    }
}
