package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
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

    // Scenario: cli/manual-run / Retired code is never returned
    // Scenario: cli/manual-run / Each terminal outcome has its exit code
    // Scenario: cli/manual-run / Each tool failure has its exit code below 10
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
        // FR9 of supervise-daemon-loops-and-embed-dashboard: a disabled standalone watch exits 1
        new DashboardDisabledException() | 1
        // FR6 of add-doctor-command: a doctor run with a fail result exits 1
        new DoctorFailedException(2) | 1
    }

    // FR5, FR8 of remove-interactive-console: code 4 is retired — no type maps to it, so the
    // mapper's whole range is the table above plus the fallback.
    // Scenario: cli/manual-run / Retired code is never returned
    def "the retired code 4 is never returned"() {
        expect:
        [
            new UsageException('bad flag'),
            new PipelineLoadFailedException(['error line']),
            new CloneRefFormatRefusedException('/clones/x', 'reftable', UntrustedText.subprocess('files')),
            new ContainerRuntimeUnavailableException('the Docker runtime is unreachable'),
            new RuntimeException('unexpected')
        ].every { mapper.getExitCode(it) != 4 }
    }

    // FR14 of add-sandbox-base-image: a precondition is the operator's configuration, never the
    // generic internal-error fallback it fell into before.
    // Scenario: cli/manual-run / A precondition refusal in run exits 8, never 1
    // The four share code 8 with take; every kind the classifier names (design D22).
    def "a #kind refusal maps to 8, not the fallback 1"() {
        expect:
        mapper.getExitCode(OperatorPreconditionRefusalSamples.of(kind)) == 8

        where:
        kind << OperatorPreconditionRefusalSamples.kinds()
    }

    // Scenario: cli/manual-run / Each tool failure has its exit code below 10
    def "getExitCode falls back to 1 for an unrecognized Throwable"() {
        expect:
        mapper.getExitCode(new RuntimeException('unexpected')) == 1
        mapper.getExitCode(new IllegalStateException('surprise')) == 1
    }
}
