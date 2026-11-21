package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.AnswerReading
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The path an agent round hands in {@code GNOMISH_ANSWER_FILE} (FR9 of extract-agent-round-runner,
 * design D3): the one path a {@code writeExactly} policy names — in a branch mode the round's answer
 * file under {@code .gnomish-task/}, {@link EnvelopePaths#answerFile} — so the decision request the
 * agent writes there is the file the branch carries, and the runner reads it back from that path;
 * any other policy gets a fresh path in the environment's scratch area.
 */
@Timeout(60)
class AgentRoundAnswerFileSpec extends Specification {

    private static final String ENVELOPE_ANSWER =
    EnvelopePaths.answerFile(new AttemptKey('TASK-7', 'implement', 1), RoundToken.of('abc123')).relative()

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    RunnerFixture fixture

    def setup() {
        fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)
    }

    // FR9, D3: the writeExactly path is the answer file the CLI is told, and the one read back
    def "FR9: a writeExactly request hands its path as the answer file and reads the decision request from there"() {
        given: 'a transform request whose one writable path is the round\'s envelope answer file'
        def request = RunnerFixture.request(Role.TRANSFORM, new Policy.WriteExactly(ENVELOPE_ANSWER))

        when: 'the agent writes a decision request at the path it was handed, relative to its working copy'
        def result = fixture.start(request, RunnerFixture.exited(0, 'done\n'), { Path answer, Path err ->
            def file = workspace.resolve(answer.toString())
            Files.createDirectories(file.parent)
            Files.writeString(file, '{"kind": "decision-request", "question": "which db?", "options": ["pg", "sqlite"]}')
        })

        then: 'the CLI was told exactly that path'
        fixture.agentLaunches.first().environment()['GNOMISH_ANSWER_FILE'] == ENVELOPE_ANSWER

        and: 'the round completed with the request read from it'
        result.status() == Status.COMPLETED
        def reading = Answer.read(result.answer(), { String text ->
            UntrustedText.agent(text)
        })
        reading instanceof AnswerReading.Parsed
        def asked = (reading as AnswerReading.Parsed).answer() as Answer.DecisionRequest
        asked.question().raw() == 'which db?'
        asked.options()*.raw() == ['pg', 'sqlite']

        and: 'the file stays where the branch carries it'
        Files.exists(workspace.resolve(ENVELOPE_ANSWER))
    }

    // FR9, D3: with no writeExactly path the answer channel is the contract's per-request scratch file
    def "FR9: a #policy request hands a fresh path in the environment's scratch area"() {
        when:
        fixture.start(RunnerFixture.request(role, policy), RunnerFixture.exited(0, 'done\n'))

        then:
        def handed = fixture.agentLaunches.first().environment()['GNOMISH_ANSWER_FILE']
        handed.contains('/answer-fake-')
        !handed.startsWith(EnvelopePaths.DIR)

        where:
        role | policy
        Role.TRANSFORM | new Policy.WorkspaceWrite()
        Role.VERDICT | new Policy.ReadOnly()
    }
}
