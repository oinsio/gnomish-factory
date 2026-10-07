package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR9, FR12, D10 of add-git-workflow, add-manual-run: every terminal exception type maps to
 * its documented exit code, including a fallback of 1 for anything unrecognized. FR7 of
 * make-run-headless: 10 and 11 come from the stop the run ended on, not from an EOF; FR4: a resume
 * refused for want of a decision is the same exit 10.
 */
class RunExitCodeMapperSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('build')
    private static final TerminalOutcomeRender.ReturnPath RETURN_PATH =
    new TerminalOutcomeRender.ReturnPath(Path.of('/work/clone'), 'manual-1')

    private RunExitCodeMapper mapper = new RunExitCodeMapper()

    def "getExitCode maps #exception.class.simpleName to #expectedCode"() {
        expect:
        mapper.getExitCode(exception) == expectedCode

        where:
        exception | expectedCode
        new UsageException('bad flag') | 2
        new PipelineLoadFailedException(['error line']) | 3
        new DivergedBranchException('PROJ-1', 'gnomish/PROJ-1', 'aaa', 'bbb') | 5
        new TaskNotFoundException('PROJ-1') | 6
        new BranchShapeRefusedException('PROJ-1', new BranchShape.Corrupt('state.json: truncated')) | 7
        new RunParkedException(new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3)), null) | 10
        new DecisionRequiredException(new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3)), RETURN_PATH) | 10
        new RunParkedException(new TaskOutcome.Paused(STATE, 'build'), null) | 11
        new AbortedException('persist failed') | 12
        new InternalErrorException('mismatch') | 1
    }

    // FR5, FR8 of remove-interactive-console: code 4 is retired — no type maps to it, so the
    // mapper's whole range is the table above plus the fallback.
    def "the retired code 4 is never returned"() {
        expect:
        [
            new UsageException('bad flag'),
            new PipelineLoadFailedException(['error line']),
            new RuntimeException('unexpected')
        ].every { mapper.getExitCode(it) != 4 }
    }

    def "getExitCode falls back to 1 for an unrecognized Throwable"() {
        expect:
        mapper.getExitCode(new RuntimeException('unexpected')) == 1
        mapper.getExitCode(new IllegalStateException('surprise')) == 1
    }
}
