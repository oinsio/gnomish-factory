package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * FR18, D11 of add-tracker-port (task 5.11): a fresh {@code Completed} outcome must render the
 * real final report (task/stage/attempts/usage via {@link com.github.oinsio.gnomish.status.StatusReport}
 * and {@link com.github.oinsio.gnomish.status.StatusTextRenderer}, plus the task branch name) and
 * actually call {@code tracker.finish} — {@link TakeEngineExecution} previously fell through to
 * {@code TakeOutcomeMapper#map}'s placeholder ("Task completed.") without ever calling the tracker.
 */
class TakeFinishReportSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'Fix the widget', 'body', List.<Decision> of())
    static final TaskState STATE = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())
    static final String BRANCH = 'gnomish/PROJ-1'

    Tracker tracker = Mock()

    // FR18, D11: finish is called with a non-blank summary rendered from StatusReport.
    def "finish renders a full report and calls tracker.finish"() {
        given:
        def completed = new TaskOutcome.Completed(STATE)

        when:
        def result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF)

        then:
        1 * tracker.finish(REF, { String summary ->
            summary.contains('PROJ-1') &&
            summary.contains('Fix the widget') &&
            summary.contains('pipeline complete') &&
            summary.contains(BRANCH)
        })

        and:
        result instanceof TakeResult.Delivered
        def delivered = result as TakeResult.Delivered
        delivered.finalState() == STATE
        delivered.summary().contains(BRANCH)
    }

    // FR18, D11: the returned TakeResult carries exactly the summary text passed to tracker.finish.
    def "finish returns a Delivered result whose summary matches the tracker.finish call"() {
        given:
        def completed = new TaskOutcome.Completed(STATE)
        String captured = null

        when:
        def result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF)

        then:
        1 * tracker.finish(REF, _ as String) >> { TaskRef ref, String summary -> captured = summary }

        and:
        (result as TakeResult.Delivered).summary() == captured
    }
}
