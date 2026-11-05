package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.untrustedtext.Provenance
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The exit-code contract of the {@code command} built-in (FR6, FR7, NFR-O1, UX1 of add-command-executor;
 * design D1, D4): every way a command run ends, reported as the contract's result. Exit 0 is completed
 * ({@code CommandStageExecutorSpec}); 126 and 127, a refused start, an expired bound and an interrupted
 * wait are {@code failed/infrastructure}; every other exit is {@code failed/quality}, whose reason —
 * the round's one finding — names the exit code and quotes the sanitized, bounded tail of the output.
 *
 * <p>Each run's handle is a {@link CannedCommandHandle}: the fake process has printed and exited before
 * the round starts, and its wait reports the ending the feature chose, so a timeout passes in virtual
 * time and no order is left to the scheduler.
 */
@Timeout(30)
class CommandExitCodeSpec extends Specification {

    static final String BUILD_ERRORS = 'Compiling 3 files\nFoo.java:3: error: cannot find symbol\nBUILD FAILED\n'

    def factory = new CommandExecutorFactory(new VirtualClock())

    private ExecutorResult start(int code, String output = '', ExecHandle.Wait wait = new ExecHandle.Wait.Exited(Duration.ofSeconds(4)),
            Map<String, Object> settings = [:]) {
        def handle = new CannedCommandHandle(code, wait, output)
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> handle
        }
        factory.executor(environment).start(factory.request(CommandFixtures.round(CommandFixtures.stage(settings))))
    }

    // Scenario: executor/command-executor / Non-zero exit is a quality failure with the output tail
    def "FR6, UX1: a command that exits 1 after printing build errors is failed/quality, its reason naming exit code 1 and quoting the tail"() {
        when:
        def result = start(1, BUILD_ERRORS)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw() == "command 'make build' failed with exit code 1; its output ended with:\n" +
                'Compiling 3 files\nFoo.java:3: error: cannot find symbol\nBUILD FAILED'

        and: 'the reason passes the captured output along, so it keeps the capture\'s family'
        result.reason().provenance() == Provenance.SUBPROCESS

        and: 'a known zero of model use (D8: REPORTED, never ABSENT), and the wall time is the run\'s'
        result.usage().provenance() == UsageProvenance.REPORTED
        result.usage().wallMillis() == 4000L
        result.usage().models().isEmpty()
    }

    def "FR6: exit code #code of a started, finished process is failed/quality"() {
        when:
        def result = start(code, 'boom\n')

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw().startsWith("command 'make build' failed with exit code ${code};".toString())

        where:
        code << [1, 2, 125, 128, 137, 255]
    }

    // Scenario: executor/command-executor / Captured output is sanitized
    def "NFR-O1, UX1: ANSI colour and control sequences in the output are stripped from the finding, its lines kept"() {
        given:
        def noisy = '\u001b[1m> Task :compileJava\u001b[0m\n\u001b[31mFoo.java:3: error\u001b[0m\u0007\n\u001b]0;title\u0007BUILD FAILED\n'

        when:
        def reason = start(1, noisy).reason().raw()

        then:
        reason.endsWith('\n> Task :compileJava\nFoo.java:3: error\nBUILD FAILED')
        !reason.contains('\u001b')
        !reason.contains('\u0007')
    }

    def "NFR-O1: a tail longer than the quote's bound is cut to its last characters, the cut marked"() {
        given: '120 numbered lines of 60 characters: 7320 characters, inside the core\'s 10 KB but over the quote'
        def lines = (1..120).collect {
            String.format('%03d', it) + ' ' + 'x' * 55
        }
        def output = lines.join('\n') + '\n'

        when:
        def reason = start(2, output).reason().raw()
        def tail = reason.substring(reason.indexOf('ended with:\n') + 'ended with:\n'.length())

        then:
        tail.startsWith('[truncated')
        tail.length() <= CommandRoundReport.TAIL_CAP_CHARS + 100
        tail.length() > CommandRoundReport.TAIL_CAP_CHARS
        tail.endsWith(lines.last())
        !tail.contains(lines.first())
    }

    def "FR6: a command that fails having printed nothing says so, in the manifest's family alone"() {
        when:
        def reason = start(3, '').reason()

        then:
        reason.raw() == "command 'make build' failed with exit code 3; it printed nothing"
        reason.provenance() == Provenance.MANIFEST
    }

    // Scenario: executor/command-executor / Unrunnable command is infrastructure
    // Scenario: executor/command-executor / Non-executable command is infrastructure
    def "FR7: exit #code is failed/infrastructure naming the command as one that cannot execute (#meaning)"() {
        when:
        def result = start(code, "sh: 1: make: ${meaning}\n")

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "command 'make build' cannot execute: exit code ${code} (${meaning}); its output ended with:\n" +
                "sh: 1: make: ${meaning}"
        result.usage().wallMillis() == 4000L

        where:
        code | meaning
        126 | 'not executable'
        127 | 'not found'
    }

    // Scenario: executor/command-executor / Unrunnable command is infrastructure
    def "FR7: a command the environment cannot start at all is failed/infrastructure naming the command and the refusal"() {
        given:
        def environment = Stub(TaskExecutionEnvironment) {
            exec(_) >> {
                throw new ProcessStartException('could not start process: sh\u001b[2J', new IOException('gone'))
            }
        }

        when:
        def result = factory.executor(environment).start(factory.request(CommandFixtures.round()))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "command 'make build' could not be started: could not start process: sh"
        result.reason().provenance() == Provenance.MANIFEST
        result.usage().wallMillis() == null
    }

    def "FR7: a command that outlives its roundTimeout is failed/infrastructure, its killed tree and its bound named, measured in virtual time"() {
        when:
        def result = start(137, 'still compiling\n', new ExecHandle.Wait.TimedOut(), [roundTimeout: 'PT10M'])

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "command 'make build' outlived its roundTimeout of PT10M and its process tree was killed; " +
                "its output ended with:\nstill compiling"
        result.usage().wallMillis() != null
    }

    def "FR7: an interrupted wait is failed/infrastructure reported as an interruption, never as an expired bound"() {
        when:
        def result = start(137, '', new ExecHandle.Wait.Interrupted())

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "command 'make build' was interrupted and its process tree was killed; it printed nothing"
        !result.reason().contains('roundTimeout')
    }
}
