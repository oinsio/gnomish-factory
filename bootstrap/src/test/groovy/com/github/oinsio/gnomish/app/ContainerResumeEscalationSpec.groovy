package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * FR6, FR25, D19, UX2 of add-sandbox-core; FR3, FR4, FR9 of make-run-headless: {@link
 * ContainerResumeRunner}'s escalated-outcome continuation, daemon-free over {@link
 * ContainerResumeSpecBase}'s scripted fixture — the operator's {@code --decision} resolved through
 * the same {@code EscalationResume} the host path uses and committed factory-side over bare git
 * objects before any environment materializes, the resumed drive reaching Completed; a {@code
 * DecisionNeeded} resumed without a decision refused with nothing written; a decision over a
 * non-escalated outcome refused as a usage error with nothing written.
 */
class ContainerResumeEscalationSpec extends ContainerResumeSpecBase {

    private static final EscalationReport QUESTION =
    new EscalationReport.DecisionNeeded(UntrustedText.agent('how?'), [UntrustedText.agent('a')])

    private void recordEscalated(String taskId, EscalationReport report = QUESTION) {
        repository.createTask(context(taskId), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        repository.recordOutcome(taskId, new TaskOutcome.Escalated(pipelineEndState(), report), TrackerWrite.OWED)
        commitStateAtPipelineEnd(taskId)
    }

    private String tipOf(String taskId) {
        gitOutput(cloneDir, 'rev-parse', TaskIdSanitizer.branchName(taskId)).trim()
    }

    // FR6, FR25, D19; FR3 of make-run-headless: --decision over an escalated task is committed
    // factory-side over bare objects, and the continuation drives to Completed.
    def "resuming an escalated task with --decision appends the operator decision factory-side and completes"() {
        given: 'an escalated task parked at PipelineEnd, so the resumed drive completes at once'
        recordEscalated('T-ESC')

        when:
        resume('T-ESC', 'fix applied', sink())

        then: 'the branch carries the completed outcome below the cleanup tip, decision included'
        def taskJson = taskJsonBelowTip('T-ESC')
        taskJson.contains('"completed"')
        taskJson.contains('fix applied')
    }

    // FR6; FR4 of make-run-headless: no --decision over an AttemptsExhausted park resumes after an
    // environment fix on the reset alone, appending no decision.
    def "resuming an escalated task without --decision appends no decision"() {
        given:
        recordEscalated('T-BLANK', new EscalationReport.AttemptsExhausted(3))

        when:
        resume('T-BLANK', null, sink())

        then:
        def taskJson = taskJsonBelowTip('T-BLANK')
        taskJson.contains('"completed"')
        taskJson.contains('"decisions":[]')
    }

    // FR4 of make-run-headless: a DecisionNeeded resumed without --decision is refused — the
    // question restated, the branch tip untouched, no box started.
    def "resuming a DecisionNeeded without --decision restates the question and writes nothing"() {
        given:
        recordEscalated('T-ASK')
        def tipBefore = tipOf('T-ASK')
        def consoleOut = new ByteArrayOutputStream()

        when:
        resume('T-ASK', null, new PrintStream(consoleOut, true, 'UTF-8'))

        then:
        thrown(DecisionRequiredException)
        consoleOut.toString('UTF-8').contains('how?')
        consoleOut.toString('UTF-8').contains('--resume=T-ASK')

        and: 'nothing was written and no box was touched'
        tipOf('T-ASK') == tipBefore
        docker.runs.isEmpty()
    }

    // FR9 of make-run-headless: a --decision over a paused task is a usage error naming the
    // conflict, raised after task.json is read and before any branch write or box.
    def "a --decision over a paused task is a usage error that writes nothing"() {
        given:
        repository.createTask(context('T-PAUSED-D'), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        repository.recordOutcome('T-PAUSED-D', new TaskOutcome.Paused(pipelineEndState(), 'build'), TrackerWrite.OWED)
        commitStateAtPipelineEnd('T-PAUSED-D')
        def tipBefore = tipOf('T-PAUSED-D')

        when:
        resume('T-PAUSED-D', 'no question was asked', sink())

        then:
        def e = thrown(UsageException)
        e.message.contains('--decision')
        e.message.contains('T-PAUSED-D')
        e.message.contains('paused')

        and:
        tipOf('T-PAUSED-D') == tipBefore
        docker.runs.isEmpty()
    }

    // NFR-R1: an escalated outcome without a recorded lastEscalation is a broken invariant —
    // an internal error naming the task, never a resolution over a missing report.
    def "an escalated outcome without a recorded escalation is an internal error"() {
        given:
        repository.createTask(context('T-NOREP'), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        commitTaskJson('T-NOREP', new TaskOutcome.Escalated(pipelineEndState(), QUESTION), null)

        when:
        resume('T-NOREP', null, sink())

        then:
        def e = thrown(InternalErrorException)
        e.message.contains('T-NOREP')
    }
}
