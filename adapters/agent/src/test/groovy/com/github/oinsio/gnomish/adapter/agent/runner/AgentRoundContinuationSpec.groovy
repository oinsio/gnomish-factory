package com.github.oinsio.gnomish.adapter.agent.runner

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.executorcontract.Continuation
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Finding
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * Decision continuation through the agent round runner (FR9, NFR-R2, design D4 of
 * extract-agent-round-runner), with {@link FakeDialect}: a {@code decision-request} answer leaves the
 * round carrying the dialect's session as its continuation key — the dialect's, never one the agent
 * wrote — and a {@code start} carrying a continuation whose resume the CLI refuses is run again as a
 * fresh round, with the reply in its feedback and one WARN naming the fallback. Every CLI is a {@code
 * FakeProcess} that has exited before the runner reads it.
 */
@Timeout(60)
class AgentRoundContinuationSpec extends Specification {

    static final ObjectMapper JSON = new ObjectMapper()

    static final String QUESTION = '{"kind":"decision-request","question":"left or right?","options":["left","right"]'

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    def "FR9: a decision request leaves the round with the dialect's session as its continuation key, #what"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, stdout), { Path answer, Path err ->
            Files.writeString(answer, written)
        })

        then:
        result.status() == Status.COMPLETED
        def answer = JSON.readTree(result.answer().toString())
        answer.path('continuationKey').isMissingNode() == (key == null)
        key == null || answer.continuationKey.asText() == key
        answer.question.asText() == 'left or right?'
        answer.options*.asText() == ['left', 'right']

        where:
        what | stdout | written || key
        'the agent having written none' | 'hello m session-9\ndone\n' | QUESTION + '}' || 'session-9'
        'replacing the one the agent wrote' | 'hello m session-9\ndone\n' | QUESTION + ',"continuationKey":"forged"}' || 'session-9'
        'and none when the round named no session' | 'done\n' | QUESTION + ',"continuationKey":"forged"}' || null
    }

    def "FR9: an answer that is not a decision request is left as the agent wrote it"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'hello m session-9\ndone\n'), { Path answer, Path err ->
            Files.writeString(answer, written)
        })

        then:
        result.answer().toString() == written

        where:
        written << [
            '{"kind":"select","chosen":"a","ranking":["a"]}',
            '{"kind":"decision-request"}'
        ]
    }

    def "FR9: a start carrying a continuation is rendered with it, one launch"() {
        when:
        def result = fixture.start(continued(), RunnerFixture.exited(0, 'hello m session-9\ndone\n'))

        then:
        result.status() == Status.COMPLETED
        fixture.agentLaunches*.command() == [
            [
                'fake-agent',
                'transform',
                '--resume',
                'session-9'
            ]
        ]
        fixture.stdinText() == 'take the left one'
    }

    // Scenario: executor/agent-round-runner / Unknown session falls back
    def "NFR-R2: a resume the CLI refuses falls back to a fresh round with the reply in feedback, at WARN"() {
        when:
        def result = null
        def logged = LogCaptureSupport.capture(SessionContinuation, Level.INFO) {
            result = fixture.startEach(continued(), [
                RunnerFixture.exited(1, ''),
                RunnerFixture.exited(0, 'hello m session-10\ndone\n')
            ], { Path answer, Path err, int launch ->
                if (launch == 0) {
                    Files.writeString(err, 'unknown session session-9\n')
                }
            })
        }

        then: 'never an infrastructure failure: the fresh round is the result'
        result.status() == Status.COMPLETED

        and: 'the second launch is a fresh start, the reply in its feedback'
        fixture.agentLaunches*.command() == [
            [
                'fake-agent',
                'transform',
                '--resume',
                'session-9'
            ],
            ['fake-agent', 'transform']
        ]
        fixture.stdins == [
            'take the left one',
            'build it'
        ]
        def fresh = fixture.dialect.rendered[1]
        fresh.continuation() == null
        fresh.feedback().size() == 2
        fresh.feedback()[0] == FEEDBACK
        fresh.feedback()[1].message().contains('take the left one')

        and: 'one WARN names the fallback and the session'
        logged.size() == 1
        logged[0].level == Level.WARN
        logged[0].formattedMessage.startsWith(OperatorEvent.AGENT_RESUME_REFUSED.head())
        logged[0].formattedMessage.contains('session-9')
        logged[0].formattedMessage.contains('fresh')
    }

    def "NFR-R2: a continued round that fails for another reason is failed/infrastructure, with no second launch"() {
        when:
        def result = fixture.start(continued(), RunnerFixture.exited(1, ''), { Path answer, Path err ->
            Files.writeString(err, 'boom: the credentials were refused\n')
        })

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        fixture.agentLaunches.size() == 1
    }

    def "NFR-R2: a round with no continuation is never taken for a refused resume"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(1, ''), { Path answer, Path err ->
            Files.writeString(err, 'unknown session session-9\n')
        })

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        fixture.agentLaunches.size() == 1
    }

    static final Finding FEEDBACK = new Finding('an earlier finding', null, null)

    private static ExecutorRequest continued() {
        def base = RunnerFixture.request(Role.TRANSFORM)
        new ExecutorRequest(base.role(), base.workspace(), base.policy(), base.roleInputs(), base.settings(), base.bounds(),
                [FEEDBACK], new Continuation('session-9', 'take the left one'))
    }
}
