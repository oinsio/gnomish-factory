package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * UX3 of add-manual-run: how a failed run REPORTS itself. Every known failure family gets a calm,
 * single line on stderr instead of a stack trace, and every one of them is rethrown unchanged so
 * {@code RunExitCodeMapper} still maps the exit code — the reporting must never change what the
 * process exits with. Two families are deliberately quiet: a task-not-found already printed its own
 * message, and an interruption is a lifecycle signal rather than a reportable failure.
 *
 * <p>Added by task 8.7 of split-into-modules (design D13(c)).
 */
class RunExceptionReportingSpec extends Specification {

    private static final Logger LOG = LoggerFactory.getLogger(RunExceptionReportingSpec)

    /** What the reporter rethrew, captured so a scenario can assert it travelled through UNCHANGED. */
    private Throwable rethrown = null

    /**
     * Runs {@code action} through the reporter and returns what it wrote to stderr. The rethrow is
     * captured rather than propagated, since both halves — the line and the exception — belong to
     * the same scenario and a propagating call would lose the captured output.
     */
    private String reportOf(RunExceptionReporting.ThrowingAction action) {
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')
        rethrown = null
        try {
            RunExceptionReporting.run(action, LOG)
        } catch (Throwable t) {
            rethrown = t
        } finally {
            System.err = originalErr
        }
        captured.toString('UTF-8')
    }

    // UX3: a clean run reports nothing at all — the reporter is a failure path, not a wrapper that
    // announces itself. And the action really runs: a reporter that skipped it would silently turn
    // every invocation into a no-op.
    def "runs the action and reports nothing when it succeeds"() {
        given:
        def ran = false

        when:
        def output = reportOf({
            ran = true
        } as RunExceptionReporting.ThrowingAction)

        then:
        ran
        output.isEmpty()
    }

    // UX3: the operator-error families print their OWN message — they were raised with text written
    // for a human, so wrapping or replacing it would lose the diagnosis.
    def "prints the exception's own message for the operator-error families"() {
        when:
        def output = reportOf({
            throw failure
        } as RunExceptionReporting.ThrowingAction)

        then: 'the message is printed, and the exception itself travels on unchanged'
        output.trim() == failure.message
        rethrown.is(failure)

        where:
        failure << [
            new UsageException('bad flag'),
            new PipelineLoadFailedException([
                'broken .gnomish/pipeline.yaml'
            ]),
            new InternalErrorException('corrupt branch'),
            new UnsupportedStateFileVersionException('task.json', 2, 1)
        ]
    }

    // UX3: an exhausted input is reported in the reporter's own words — the exception's message is
    // an internal detail, while "stopping" is what the operator needs to understand.
    def "reports an exhausted input in its own words"() {
        when:
        def output = reportOf({
            throw failure
        } as RunExceptionReporting.ThrowingAction)

        then:
        output.trim() == 'Input exhausted — stopping.'
        rethrown.is(failure)

        where:
        failure << [
            new InputExhaustedException(),
            new ConsoleClosedException()
        ]
    }

    // UX3, design D15: a task-not-found already printed a calm message on stdout, so printing a
    // second line here would say the same thing twice.
    def "stays silent for a task that was not found"() {
        when:
        def output = reportOf({
            throw new TaskNotFoundException('PROJ-1')
        } as RunExceptionReporting.ThrowingAction)

        then:
        output.isEmpty()
        rethrown instanceof TaskNotFoundException
    }

    // UX3: anything unclassified is the generic fallback — named as a run failure and carrying the
    // cause's message, so an unexpected fault is still legible without a stack trace.
    def "reports an unclassified failure as a run failure, carrying its message"() {
        given: 'the reporter logs through the caller-supplied logger, which here is this spec class'
        def logs = LogCaptureSupport.attach(RunExceptionReportingSpec)

        when:
        def output = reportOf({
            throw new IllegalStateException('boom')
        } as RunExceptionReporting.ThrowingAction)

        then:
        output.contains('gnomish run failed: boom')
        rethrown instanceof IllegalStateException

        and: 'FR15 of harden-logging-observability: the calm stderr line is backed by a coded WARN carrying the stack'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.RUN_UNHANDLED_EXCEPTION.head())
        }
        event != null
        event.level == Level.WARN
        event.throwableProxy != null

        cleanup:
        logs.detach()
    }

    // UX3: an I/O fault takes the same fallback — it is a checked exception the action may throw,
    // and it must not escape unreported.
    def "reports an I/O fault through the same fallback"() {
        when:
        def output = reportOf({
            throw new IOException('disk on fire')
        } as RunExceptionReporting.ThrowingAction)

        then:
        output.contains('gnomish run failed: disk on fire')
        rethrown instanceof IOException
    }

    // Interruption is a lifecycle signal, not a failure — a `serve` feed loop interrupted mid-run
    // is a requested stop, so it is neither classified nor reported, only propagated.
    def "propagates an interruption without reporting it"() {
        when:
        def output = reportOf({
            throw new InterruptedException('stop')
        } as RunExceptionReporting.ThrowingAction)

        then:
        output.isEmpty()
        rethrown instanceof InterruptedException
    }
}
