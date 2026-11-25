package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentInvocation
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR4 of add-sandbox-core; FR9 of extract-agent-round-runner (design D3): the host-mode round
 * source opens each round as a fresh host environment over the DirectoryWorkspace root, its answer
 * file a fresh path in that environment's scratch area — outside the workspace, named to the agent in
 * {@code GNOMISH_ANSWER_FILE} — which the round's executor reads before the close, and which leaves
 * with the round's scratch area at the close. The answer file has no transport of its own.
 */
class HostRoundEnvironmentSourceSpec extends Specification {

    @TempDir
    Path workspaceDir

    HostRoundEnvironmentSource source = new HostRoundEnvironmentSource(new VirtualClock(), TestChildEnvironment.hostAllowlist())

    // FR9, D3: the round's answer file lives in its scratch area, never under the workspace, and is
    //     named to the agent by the contract's answer-file variable
    // Scenario: cli/manual-run / Decision requests travel by the mode's own channel
    def "FR9: openRound names an answer file in the round's scratch area, outside the workspace"() {
        when:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))

        then:
        round.environment() instanceof HostTaskExecutionEnvironment
        round.answerFilePath().startsWith(round.environment().scratchRoot())
        !round.answerFilePath().startsWith(workspaceDir)
        round.answerEnvFragment() == [GNOMISH_ANSWER_FILE: round.answerFilePath().toString()]

        cleanup:
        round.discard()
    }

    // FR9: every round gets a path of its own, so no round reads a request an earlier one left;
    //     the rounds are sequential, the source holding one at a time (D14 as amended 2026-11-25)
    def "FR9: two rounds get distinct answer files"() {
        when:
        def first = source.openRound(StageExecutorRequests.request(workspaceDir))
        first.closeRound(TerminalClose.VERIFIABLE)
        def second = source.openRound(StageExecutorRequests.request(workspaceDir))

        then:
        first.answerFilePath() != second.answerFilePath()

        cleanup:
        second?.discard()
    }

    // FR21 of define-executor-contract: undecorated, the host source serves the git-less in-place
    // run, so it names the in-place identity — a round it opens yields no token and reads no git
    def "FR21: the bare host source names the in-place identity, which yields no token"() {
        when:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))

        then:
        source.identity().takeToken() == Optional.empty()

        cleanup:
        round.discard()
    }

    // FR5 of define-executor-contract (task 7.1): a host round's environment has a scratch area from
    // the open to the close or discard, where a program executor names its result files; the round's
    // answer file lives there, so it leaves with the round too (FR9 of extract-agent-round-runner)
    // Scenario: executor/agent-executor / The in-place answer file leaves with the round
    def "FR5: a round's environment has a scratch area that leaves at its #ending"() {
        given:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))
        def scratch = Path.of(round.environment().scratchRoot())

        expect:
        Files.isDirectory(scratch)
        round.answerFilePath().startsWith(scratch)
        !round.answerFilePath().startsWith(workspaceDir)

        when:
        ending == 'close' ? round.closeRound(TerminalClose.VERIFIABLE) : round.discard()

        then:
        !Files.exists(scratch)

        cleanup:
        round.discard()

        where:
        ending << ['close', 'discard']
    }

    // FR9: the request the fake agent wrote leaves with the round's scratch area at the close
    def "FR9: the answer file the fake agent wrote is gone after the close"() {
        given:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))
        def run = new FakeAgentInvocation(scenario: 'decision-needed', workingDirectory: workspaceDir,
        answerFilePath: round.answerFilePath()).run()

        expect:
        run.exitCode() == 0
        Files.exists(round.answerFilePath())

        when:
        round.closeRound(TerminalClose.VERIFIABLE)

        then:
        !Files.exists(round.answerFilePath())
    }
}
