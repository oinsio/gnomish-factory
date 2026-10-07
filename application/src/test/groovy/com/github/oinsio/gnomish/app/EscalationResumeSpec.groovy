package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.console.DialogConsole
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

/**
 * FR3, FR4 of make-run-headless (design D2, D7): the one place a {@code run} resume decision becomes
 * a {@code Resumption}. The table runs every report kind with and without a decision: a decision is
 * appended and the attempts reset; no decision resets the attempts alone; a {@code DecisionNeeded}
 * without one restates the question and refuses; a {@code PipelineMismatch} is an internal error
 * either way. The class never reads the console — a read fails the spec.
 */
class EscalationResumeSpec extends Specification {

    private static final Instant NOW = Instant.parse('2026-10-06T10:00:00Z')
    private static final TaskContext CONTEXT =
    new TaskContext('manual-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    /** Two attempts burned at `build`: the reset has something to undo. */
    private static final TaskState BURNED = new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none())
    private static final TerminalOutcomeRender.ReturnPath RETURN_PATH =
    new TerminalOutcomeRender.ReturnPath(Path.of('/work/clone'), 'manual-1')

    private static final EscalationReport ATTEMPTS_EXHAUSTED = new EscalationReport.AttemptsExhausted(3)
    private static final EscalationReport DECISION_NEEDED =
    new EscalationReport.DecisionNeeded(UntrustedText.agent('which database?'), [
        UntrustedText.agent('postgres'),
        UntrustedText.agent('sqlite')
    ])
    private static final EscalationReport CANNOT_VERIFY = new EscalationReport.CannotVerify(
    new CheckRef(0, UntrustedText.manifest('command:./gradlew test')),
    UntrustedText.subprocess('timeout'), UntrustedText.subprocess('trace'))
    private static final EscalationReport PIPELINE_MISMATCH =
    new EscalationReport.PipelineMismatch(UntrustedText.branchDocument('stale-stage'))
    private static final EscalationReport CANNOT_EXECUTE =
    new EscalationReport.CannotExecute(UntrustedText.subprocess('agent crashed'), [])

    /** FR6: no path of a resume may read stdin. */
    private ScriptedConsoleIO io = new ScriptedConsoleIO() {
        @Override
        String readLine() {
            throw new AssertionError('EscalationResume read the console')
        }
    }
    private EscalationResume resume = new EscalationResume(
    new DialogConsole(io), Clock.fixed(NOW, ZoneOffset.UTC), RETURN_PATH)

    def "FR3, FR4: #report.class.simpleName with decision #decision resumes with attempts reset and the decision #appended"() {
        when:
        def resumption = resume.decide(CONTEXT, new TaskOutcome.Escalated(BURNED, report), decision)

        then: 'the state keeps its position, with the attempts reset'
        resumption.state() == BURNED.resetAttempts()
        resumption.state().attemptsUsed() == 0
        resumption.state().position() == new Position.AtStage('build')

        and: 'the context carries the decision exactly when one was given'
        resumption.context().decisions().size() == (decision == null ? 0 : 1)
        decision == null || resumption.context().decisions().first() ==
                new Decision(decision, 'build', 'operator', NOW)
        resumption.context().taskId() == CONTEXT.taskId()

        and: 'nothing is printed on the way through'
        io.printed.isEmpty()

        where:
        report | decision
        ATTEMPTS_EXHAUSTED | 'patch in place'
        ATTEMPTS_EXHAUSTED | null
        DECISION_NEEDED | 'use postgres'
        CANNOT_VERIFY | 'network is back'
        CANNOT_VERIFY | null
        CANNOT_EXECUTE | 'agent reinstalled'
        CANNOT_EXECUTE | null

        appended = decision == null ? 'absent' : 'appended'
    }

    // FR1 of make-checkpoint-gate-durable: the decision is scoped to the stage the position names —
    //     at a gate the stage that passed — and to none past the pipeline's end
    def "the decision is scoped to the stage #position names"() {
        when:
        def resumption = resume.decide(CONTEXT, new TaskOutcome.Escalated(
                        new TaskState(position, 0, [], ExecutorUsage.none()), ATTEMPTS_EXHAUSTED), 'go on')

        then:
        resumption.context().decisions() == [
            new Decision('go on', stage, 'operator', NOW)
        ]

        where:
        position | stage
        new Position.AtStage('build') | 'build'
        new Position.AwaitingApproval('release') | 'release'
        new Position.PipelineEnd() | null
    }

    def "FR4: a DecisionNeeded without a decision restates the question with the return path and refuses"() {
        given:
        def escalated = new TaskOutcome.Escalated(BURNED, DECISION_NEEDED)

        when:
        resume.decide(CONTEXT, escalated, null)

        then: 'the refusal carries the recorded escalation for the exit boundary'
        def refused = thrown(DecisionRequiredException)
        refused.outcome().is(escalated)
        refused.stopRecord() ==
                'task manual-1 still needs a decision; To continue: gnomish run --dir=/work/clone --resume=manual-1 [--decision="..."]'

        and: 'the question, the options and the return-path line are printed once, and nothing else'
        io.printed == [
            TerminalOutcomeRender.escalated(DECISION_NEEDED, RETURN_PATH) + ConsoleIO.LINE_END
        ]
        io.printed.first().contains('which database?')
        io.printed.first().contains('postgres')
    }

    def "a PipelineMismatch cannot be resumed by any decision (#decision): it is an internal error"() {
        when:
        resume.decide(CONTEXT, new TaskOutcome.Escalated(BURNED, PIPELINE_MISMATCH), decision)

        then:
        def ex = thrown(InternalErrorException)
        ex.message.contains('stale-stage')
        io.printed.isEmpty()

        where:
        decision << ['anything', null]
    }

    // FR7, FR8 of make-checkpoint-gate-durable (design D4): what decide resolved lands as exactly one
    //     lifecycle commit — the decision commit with a decision, the resumed commit without one —
    //     carrying the reset state, never a reset held in memory alone
    def "FR7: land writes the #write commit for decision #decision, once"() {
        given:
        def repository = Mock(TaskRepository)
        def resumption = resume.decide(CONTEXT, new TaskOutcome.Escalated(BURNED, ATTEMPTS_EXHAUSTED), decision)

        when:
        EscalationResume.land(repository, 'manual-1', resumption, decision)

        then:
        appends * repository.appendDecision('manual-1', new Decision('patch in place', 'build', 'operator', NOW), BURNED.resetAttempts())
        resumes * repository.resumeFrom('manual-1', BURNED.resetAttempts())
        0 * repository._

        where:
        decision | appends | resumes
        'patch in place' | 1 | 0
        null | 0 | 1

        write = decision == null ? 'resumed' : 'decision'
    }
}
