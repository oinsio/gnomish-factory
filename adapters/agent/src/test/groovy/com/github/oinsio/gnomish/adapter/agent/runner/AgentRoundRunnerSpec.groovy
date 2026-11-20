package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link AgentRoundRunner} with {@link FakeDialect} (FR1, FR2, FR8, NFR-S2 of
 * extract-agent-round-runner): every role through one launch, drain and read path; the round's exit
 * judged by the dialect — completed, a limit stop, broken — with the stderr tail on a failure; the
 * answer read back from where it was written; the working-copy check of {@code verdict} and {@code
 * decision}. Each CLI is a {@link FakeProcess} that has exited with its stdout waiting in the pipe
 * before the runner reads it, so no order between threads is assumed.
 */
@Timeout(60)
class AgentRoundRunnerSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    // Scenario: executor/agent-round-runner / Three roles, one runner
    // Scenario: executor/agent-round-runner / Second dialect needs no runner change
    def "FR1, FR2: a #role request completes through the one launch, drain and read path of the fake dialect"() {
        given:
        def stdout = 'hello fake-model-1 session-9\nuse Read\n' + answerLine + 'done all good\n'

        when:
        def result = fixture.start(RunnerFixture.request(role), RunnerFixture.exited(0, stdout), { Path answerFile, Path err ->
            if (agentWrites) {
                Files.writeString(answerFile, '{"choice":"a"}')
            }
        })

        then: 'one CLI launched, the argv and stdin the dialect rendered for the role'
        result.status() == Status.COMPLETED
        fixture.agentLaunches*.command() == [['fake-agent', role.wire()]]
        fixture.stdinText() == prompt

        and: 'the answer read back from where it was written, nothing else differing'
        result.answer()?.toString() == expected
        result.usage().provenance() == UsageProvenance.REPORTED
        fixture.progress*.getClass() == [
            AgentProgressEvent.RoundStarted,
            AgentProgressEvent.ToolStarted,
            AgentProgressEvent.RoundFinished
        ]
        fixture.dialect.judged.stamped()*.event()*.kind == ['hello', 'use'] + (answerLine ? ['answer'] : []) + ['done']

        where:
        role | answerLine | agentWrites || prompt | expected
        Role.TRANSFORM | '' | false || 'build it' | null
        Role.VERDICT | 'answer {"verdict":"pass"}\n' | false || 'is it built?' | '{"verdict":"pass"}'
        Role.DECISION | '' | true || 'which one?' | '{"choice":"a"}'
    }

    // Scenario: executor/agent-round-runner / Exit without result is infrastructure
    def "FR8: a CLI that exits non-zero with no result event is failed/infrastructure naming the code and the stderr tail"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(3, 'hello m s\nuse Read\n'), { Path answer, Path err ->
            Files.writeString(err, 'boom: the credentials were refused\n')
        })

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().startsWith("agent executor 'fake': exited 3: no result event")
        result.reason().raw().contains('its stderr ended with: boom: the credentials were refused')
        result.answer() == null
        result.usage().provenance() == UsageProvenance.REPORTED
    }

    // FR1: a refused start, an answer that does not match its schema and an unreadable answer file are each named
    def "FR1: #what is failed/infrastructure saying so"() {
        when:
        def result = fixture.run(RunnerFixture.request(Role.VERDICT), launch(stdout, written))

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw().startsWith("agent executor 'fake': ")
        result.reason().raw().contains(words)
        result.answer() == null

        where:
        what | stdout | written || words
        'a CLI the environment will not start' | null | null || 'the environment refused to start the agent CLI'
        'a final answer the dialect finds malformed' | 'answer malformed\ndone\n' | null || 'its final answer does not match the answer\'s schema: no verdict field'
        'an answer file that is not JSON' | 'done\n' | 'not json' || 'wrote a malformed answer file'
    }

    // FR1: the credentials the dialect's describe names are granted, the answer path is named, the scratch directory leaves
    def "FR1: the round's credentials and answer path reach the CLI's environment, and the round leaves no scratch directory"() {
        given:
        fixture.dialect.credentials = ['FAKE_API_KEY']

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'done\n'))

        then:
        result.status() == Status.COMPLETED
        def environment = fixture.agentLaunches.first().environment()
        environment['FAKE_API_KEY'] == 'fake-secret'
        environment['GNOMISH_ANSWER_FILE'].contains('/answer-fake-')
        Files.list(stderrParent).count() == 0
    }

    // FR1: a usage that reports its own wall time keeps it; one that reports none takes the measured one
    def "FR1: the round's usage keeps a reported wall time and otherwise takes the measured one"() {
        given:
        fixture.dialect.wallMillis = reported

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'done\n'))

        then:
        result.usage().wallMillis() == expected

        where:
        reported || expected
        4200L || 4200L
        null || 0L
    }

    // NFR-S2: a listener that throws costs a log line, never the round
    def "FR6: a progress listener that throws does not stop the round"() {
        given:
        fixture.listener = { AgentProgressEvent event ->
            throw new IllegalStateException('listener broke')
        }

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'hello m s\ndone\n'))

        then:
        result.status() == Status.COMPLETED
        fixture.dialect.parsed == ['hello m s', 'done']
    }

    // NG5: an agent round never returns pending, so a poll is refused naming why
    def "NG5: a poll of an agent round is refused, and describe is the dialect's"() {
        given:
        def runner = fixture.runner(null, null)

        expect:
        runner.describe() == fixture.dialect.describe()
        with(runner.poll(RunnerFixture.request(Role.TRANSFORM), new PendingKey('k-1'))) {
            failureClass() == FailureClass.INFRASTRUCTURE
            reason().raw() == "agent executor 'fake': an agent round cannot be polled: it never returns pending"
        }
    }

    private ProcessLaunch launch(String stdout, String written) {
        { ProcessBuilder builder, source ->
            if (builder.command().first() == 'git') {
                return RunnerFixture.exited(0, RunnerFixture.CLEAN)
            }
            if (stdout == null) {
                throw new IOException('no such file: fake-agent')
            }
            if (written != null) {
                Files.writeString(Path.of(builder.environment()['GNOMISH_ANSWER_FILE']), written)
            }
            RunnerFixture.exited(0, stdout)
        } as ProcessLaunch
    }
}
