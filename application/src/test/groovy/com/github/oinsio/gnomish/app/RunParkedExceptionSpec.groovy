package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR2, FR7, NFR-O1 of make-run-headless: the carrier a {@code run} stop leaves the process in
 * — which stop it is, and the record the log keeps of how to continue.
 */
class RunParkedExceptionSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('build')
    private static final TerminalOutcomeRender.ReturnPath RETURN_PATH =
    new TerminalOutcomeRender.ReturnPath(Path.of('/work/clone'), 'manual-1')

    def "an escalation stop is not a checkpoint and records the resume command with an optional decision"() {
        given:
        def outcome = new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))
        def stop = new RunParkedException(outcome, RETURN_PATH)

        expect:
        stop.outcome().is(outcome)
        !stop.checkpoint()
        stop.message == 'run stopped: escalated'
        stop.stopRecord() ==
                'task manual-1 stopped escalated; To continue: gnomish run --dir=/work/clone --resume=manual-1 [--decision="..."]'
    }

    def "a checkpoint stop records the resume command without a decision"() {
        given:
        def stop = new RunParkedException(new TaskOutcome.Paused(STATE, 'build'), RETURN_PATH)

        expect:
        stop.checkpoint()
        stop.message == 'run stopped: paused'
        stop.stopRecord() == 'task manual-1 stopped paused; To continue: gnomish run --dir=/work/clone --resume=manual-1'
    }

    def "an in-place stop records that there is nothing to resume"() {
        expect:
        new RunParkedException(new TaskOutcome.Paused(STATE, 'build'), null).stopRecord() ==
                'run stopped paused in in-place mode; there is no branch to resume from'
    }

    def "only a stop can be carried: #outcome.class.simpleName is refused"() {
        when:
        new RunParkedException(outcome, RETURN_PATH)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "RunParkedException carries an Escalated or Paused outcome, not ${outcome.class.simpleName}"

        where:
        outcome << [
            new TaskOutcome.Completed(STATE),
            new TaskOutcome.Aborted(STATE, new AttemptKey('manual-1', 'build', 0), UntrustedText.subprocess('lost'))
        ]
    }
}
