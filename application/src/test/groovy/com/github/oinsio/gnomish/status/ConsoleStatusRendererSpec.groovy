package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import spock.lang.Specification

/**
 * ConsoleStatusRenderer: the StatusRenderer implementation DialogConsole calls
 * for the status / status --json meta-commands, backed by a
 * StatusSnapshotHolder, a TaskContext, a StatusTextRenderer (task 6.4) and a
 * StatusReportJsonMapper (task 6.5 of add-manual-run).
 * Implements FR10, FR11, UX2, D7 of add-manual-run.
 */
class ConsoleStatusRendererSpec extends Specification {

    private static TaskContext context() {
        new TaskContext('manual-20260716-143502-x7', 'Fix flaky spec', 'body text', [])
    }

    // FR10, UX2: render(false) returns the full text render of the current snapshot
    def "render(false) returns the full text render of the held snapshot"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def ctx = context()
        def renderer = new ConsoleStatusRenderer(holder, ctx, new StatusTextRenderer())

        when:
        def text = renderer.render(false)

        then:
        text == new StatusTextRenderer().renderFull(holder.current(ctx))
        text.contains('implement')
    }

    // FR11: render(true) returns the v1 JSON contract render of the current snapshot
    def "render(true) returns the JSON render of the held snapshot"() {
        given:
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def ctx = context()
        def renderer = new ConsoleStatusRenderer(holder, ctx, new StatusTextRenderer())

        when:
        def json = renderer.render(true)

        then:
        json == new StatusReportJsonMapper().serialize(holder.current(ctx))
        json.contains('"atStage"')
    }
}
