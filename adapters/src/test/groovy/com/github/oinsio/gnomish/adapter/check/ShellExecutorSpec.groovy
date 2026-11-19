package com.github.oinsio.gnomish.adapter.check

import static com.github.oinsio.gnomish.adapter.check.ShellExecutorDescribeSpec.verdict

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.SarifResult
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR5, NFR-C1 of migrate-verify-to-executor-contract: a {@code shell} verdict run reports status and a
 * SARIF answer, never a verdict. The exit code is status: whatever results the findings path carries
 * are the answer — a SARIF log relayed, a legacy file normalized to {@code error} results — and a red
 * command that wrote none answers its synthetic tail result; a killed run is {@code failed/quality},
 * exit 126/127 {@code failed/infrastructure}. The answer is read back through the contract's own
 * answer reading, as a program's would be.
 */
class ShellExecutorSpec extends Specification {

    @TempDir
    Path tempDir

    private ExecutorResult run(String line, Duration checkTimeout = Duration.ofSeconds(30)) {
        def runner = new ShellCommandCheckRunner(new VirtualClock()).withCheckTimeout(checkTimeout)
        new ShellExecutor(runner, VerifyChecks.command(line), new DirectoryWorkspace(tempDir)).start(verdict())
    }

    private static String writing(String content, int exitCode) {
        'cat > "$GNOMISH_FINDINGS_FILE" <<\'EOF\'\n' + content + '\nEOF\nexit ' + exitCode
    }

    /** The answer's runs, read back through the contract's answer reading. */
    private static List answerOf(ExecutorResult result) {
        assert result.status() == Status.COMPLETED
        def reading = Answer.read(result.answer(), { String text ->
            UntrustedText.factory(text)
        })
        assert reading instanceof AnswerReading.Parsed
        ((reading as AnswerReading.Parsed).answer() as Answer.Verdict).runs()
    }

    /** A carrier for an expected value; carriers compare by text alone. */
    private static UntrustedText t(String text) {
        UntrustedText.factory(text)
    }

    private static List<SarifResult> resultsOf(ExecutorResult result) {
        answerOf(result).collectMany { it.results() }
    }

    // Scenario: verification/verdict-executors / Legacy findings file is normalized
    def "FR5: a legacy findings file is normalized to error results, its details beside the message"() {
        when:
        def result = run(writing('{"findings":[{"message":"x"},{"message":"y","location":"a.txt:1","details":"why"}]}', 0))

        then:
        resultsOf(result) == [
            new SarifResult(null, SarifLevel.ERROR, t('x'), null, null),
            new SarifResult(null, SarifLevel.ERROR, t('y'), t('a.txt:1'), null, t('why'))
        ]
    }

    def "FR5: a SARIF log is relayed result by result in one run named after the executor"() {
        given:
        def log = '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":[{"ruleId":"r",' +
                '"level":"note","message":{"text":"m"},"locations":[{"physicalLocation":' +
                '{"artifactLocation":{"uri":"src/A.java"},"region":{"startLine":7}}}]}]}]}'

        when:
        def result = run(writing(log, 0))

        then:
        answerOf(result)*.tool()*.forLog() == ['shell']
        resultsOf(result) == [
            new SarifResult(t('r'), SarifLevel.NOTE, t('m'), t('src/A.java'), 7)
        ]
    }

    // Scenario: verification/verdict-executors / Warnings pass under the default threshold
    def "FR5: the exit code carries no verdict: a red command whose SARIF holds only warnings completes with them"() {
        given:
        def log = '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":[{"message":{"text":"w"}}]}]}'

        when:
        def result = run(writing(log, 1))

        then: 'completed, one warning and no synthetic error — the engine passes it under the default threshold'
        resultsOf(result)*.level() == [SarifLevel.WARNING]
        SarifLevel.ERROR.passes(new Answer.Verdict(answerOf(result)))
    }

    // Scenario: verification/verdict-executors / A red command without a findings file carries its output tail
    def "FR5: a red command without a findings file answers one error result whose properties.details hold its output tail"() {
        when:
        def result = run('echo boom-output; exit 1')

        then:
        def results = resultsOf(result)
        results.size() == 1
        results[0].level() == SarifLevel.ERROR
        results[0].message().raw() == 'command exited with status 1'
        results[0].details().raw().contains('boom-output')

        and: "on the wire, the tail is the SARIF result's properties.details, apart from the message"
        def written = result.answer().path('sarif').path('runs')[0].path('results')[0]
        written.path('message').path('text').asText() == 'command exited with status 1'
        written.path('properties').path('details').asText().contains('boom-output')

        and: 'the check fails'
        !SarifLevel.ERROR.passes(new Answer.Verdict(answerOf(result)))
        result.usage().provenance() == UsageProvenance.REPORTED
        result.usage().models().isEmpty()
    }

    def "FR5: a green command with no findings completes with an empty answer"() {
        expect:
        resultsOf(run('exit 0')).isEmpty()
    }

    def "FR5: a red command that printed nothing keeps the bare sentence, with no details"() {
        when:
        def result = resultsOf(run('exit 4'))[0]

        then:
        result.message().raw() == 'command exited with status 4'
        result.details() == null
    }

    // Scenario: verification/verdict-executors / A missing binary is an infrastructure failure
    def "FR5: exit #code is failed/infrastructure, the tail as the result's details beside the reason"() {
        when:
        def result = run('echo tail-output; exit ' + code)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.answer() == null
        result.reason().raw() == reason
        result.details().raw().contains('tail-output')

        where:
        code || reason
        126 || 'command not executable (exit 126)'
        127 || 'command not found (exit 127)'
    }

    @Timeout(30)
    def "FR5: a run killed on the check timeout is failed/quality naming the timeout and its tail"() {
        when:
        def result = run('echo hung-output; while true; do sleep 1; done', Duration.ofMillis(300))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw() == ShellOutcome.TIMED_OUT
        result.details().raw().contains('hung-output')
    }

    def "FR5: an environment that cannot be served is failed/infrastructure with its reason"() {
        given:
        def unavailable = { c, w ->
            throw new CheckEnvironmentUnavailableException('fresh box could not be materialized')
        } as CheckEnvironmentSource
        def runner = new ShellCommandCheckRunner(new VirtualClock()).withEnvironments(unavailable)
        def logs = LogCaptureSupport.attach(ShellCommandCheckRunner)

        when:
        def result = new ShellExecutor(runner, VerifyChecks.command('pwd'), new DirectoryWorkspace(tempDir))
                .start(verdict())

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == 'fresh box could not be materialized'
        logs.list.any {
            it.formattedMessage.startsWith(OperatorEvent.COMMAND_CHECK_NO_ENVIRONMENT.head())
        }

        cleanup:
        logs.detach()
    }
}
