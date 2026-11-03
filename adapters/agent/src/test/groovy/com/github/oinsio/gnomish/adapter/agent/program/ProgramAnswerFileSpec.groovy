package com.github.oinsio.gnomish.adapter.agent.program

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The answer file and the refused start of a {@code program} run (FR3, FR5, FR6 of
 * define-executor-contract): {@code GNOMISH_ANSWER_FILE} carries the answer when the result's slot is
 * empty, and only then; a malformed one is the run's {@code failed/infrastructure}; a process the
 * environment will not start is one too; the stderr tail is a DEBUG line of a completed run only.
 */
class ProgramAnswerFileSpec extends Specification {

    static final String COMPLETED = StandIn.data('executor-result#completed')

    static final String DECISION = '{"kind":"decision-request","question":"Which?","options":["a","b"]}'

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private FakeProcessRun run() {
        new FakeProcessRun(workspace: workspace, stderrParent: stderrParent)
    }

    // FR3, FR5: the answer file carries the answer of a result whose own slot is empty
    def "FR3: a completed result with no answer of its own takes the answer file's"() {
        when:
        def result = run().start(new FakeProcess(), { Path out, Path answer, Path err ->
            Files.writeString(out, COMPLETED)
            Files.writeString(answer, DECISION)
        })

        then:
        result.status() == Status.COMPLETED
        result.answer().get('question').textValue() == 'Which?'
    }

    // FR3: the result's own answer wins; a result that could not be read reads no answer file
    def "FR3: the answer file is not read when the #why"() {
        when:
        def result = run().start(new FakeProcess(), { Path out, Path answer, Path err ->
            Files.writeString(out, written)
            Files.writeString(answer, DECISION)
        })

        then:
        (result.answer()?.get('kind')?.textValue()) == kind

        where:
        why | written || kind
        'result carries its own' | '{"status":"completed","answer":{"kind":"select","chosen":"a"},"usage":{"provenance":"ABSENT"}}' || 'select'
        'result could not be read' | '{"status":' || null
    }

    // Scenario: executor/wire-contract / Malformed verdict is cannot-verify
    // FR6: a malformed answer file is never a pass
    def "FR6: a malformed answer file is failed/infrastructure naming it"() {
        when:
        def result = run().start(new FakeProcess(), { Path out, Path answer, Path err ->
            Files.writeString(out, COMPLETED)
            Files.writeString(answer, '{"kind":')
        })

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('wrote a malformed answer file')
    }

    // FR5: a process the environment will not start is the run's failed/infrastructure, not a throw
    def "FR5: a refused start is failed/infrastructure naming the refusal"() {
        when:
        def result = run().run({ ProcessBuilder builder, StdinSource stdin ->
            throw new IOException('no such file: acme-program')
        } as ProcessLaunch)

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the environment refused to start the program')
        result.reason().contains('acme-program')
    }

    // FR5: only a completed run leaves its stderr tail as a DEBUG line; a failed one quotes it instead
    def "FR5: the stderr tail is a DEBUG line for a completed run and none for a failed one"() {
        given:
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        run().start(new FakeProcess(), { Path out, Path answer, Path err ->
            Files.writeString(err, 'noise')
        })

        then:
        logs.list.isEmpty()

        when:
        run().start(new FakeProcess(), { Path out, Path answer, Path err ->
            Files.writeString(err, 'noise')
            Files.writeString(out, COMPLETED)
        })

        then:
        logs.list.size() == 1
    }
}
