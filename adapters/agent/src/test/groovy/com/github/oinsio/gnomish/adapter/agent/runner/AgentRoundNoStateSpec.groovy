package com.github.oinsio.gnomish.adapter.agent.runner

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * An agent round keeps no state past its end (FR9, NFR-R2 of extract-agent-round-runner; design D4 as
 * amended 2026-11-21): a {@code decision-request} answer leaves the runner with its question and
 * options and no continuation key, one the agent wrote removed — whether the agent wrote the answer
 * file or the dialect extracted it from the stream, and whatever session the stream named — and no
 * agent executor built from the runner declares {@code resumable}. Every CLI is a {@code FakeProcess}
 * that has exited before the runner reads it.
 */
@Timeout(60)
class AgentRoundNoStateSpec extends Specification {

    static final ObjectMapper JSON = new ObjectMapper()

    static final String ASKED = '{"kind":"decision-request","question":"left or right?","options":["left","right"]'

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    // Scenario: executor/agent-round-runner / An agent-written continuation key is removed
    def "FR9: a decision request leaves the round with its question and options and no continuation key, #what"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, stdout), { Path answer, Path err ->
            if (written != null) {
                Files.writeString(answer, written)
            }
        })

        then:
        result.status() == Status.COMPLETED
        def answer = JSON.readTree(result.answer().toString())
        answer.kind.asText() == 'decision-request'
        answer.question.asText() == 'left or right?'
        answer.options*.asText() == ['left', 'right']
        answer.path('continuationKey').isMissingNode()

        where:
        what | stdout | written
        'the agent having written a key to the answer file' | 'hello m session-9\ndone\n' | ASKED + ',"continuationKey":"forged"}'
        'with no session on the stream' | 'done\n' | ASKED + ',"continuationKey":"forged"}'
        'the agent having written none' | 'hello m session-9\ndone\n' | ASKED + '}'
        'the dialect having extracted it, key included' | "hello m session-9\nanswer ${ASKED},\"continuationKey\":\"forged\"}\ndone\n" | null
    }

    // FR21 (design D15): another kind of answer leaves as the contract's answer, untouched by the
    //     key removal; a document that is no answer is refused by the one read, never passed on
    def "FR9, FR21: an answer that is not a decision request keeps its fields, and no answer at all is refused"() {
        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'hello m session-9\ndone\n'), { Path answer, Path err ->
            Files.writeString(answer, written)
        })

        then:
        result.answer()?.toString() == carried
        result.status() == status

        where:
        written | carried | status
        '{"kind":"select","chosen":"a","ranking":["a"]}' | '{"kind":"select","chosen":"a","ranking":["a"]}' | Status.COMPLETED
        '{"kind":"decision-request","continuationKey":"kept"}' | null | Status.FAILED
    }

    // Scenario: executor/agent-round-runner / No agent executor declares resumable
    def "FR9: the describe of an agent executor over the #dialectName dialect does not declare resumable"() {
        given:
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(new VirtualClock()),
                ChildEnvAllowlist.none().programGrant(), stderrParent, LastRegisteredCommit.NONE)
        def runner = new AgentRoundRunner(dialectName, dialect, Stub(TaskExecutionEnvironment), equipment,
                {} as AgentProgressListener, StdoutCaps.DEFAULTS)

        expect:
        !runner.describe().capabilities().contains(Capability.RESUMABLE)
        runner.describe() == dialect.describe()

        where:
        dialectName | dialect
        'fake' | new FakeDialect()
        'claude' | ClaudeDialect.of('claude', UntrustedText.subprocess('2.1.0'),
                ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf([:])))
    }
}
