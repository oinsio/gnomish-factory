package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR4 of add-sandbox-core: the host-mode round environment source opens each round as a
 * fresh host environment over the DirectoryWorkspace root with the temp-dir decision-file
 * transport — the round hands the executor the transport's decision-file path and the matching
 * {@code GNOMISH_DECISION_FILE} env fragment.
 */
class HostRoundEnvironmentSourceSpec extends Specification {

    @TempDir
    Path workspaceDir

    @TempDir
    Path decisionRoot

    // FR2, FR4: the round's decision-file path is the transport's, never null — the executor
    // wires it into the CLI flags' pinpoint Write allowance and the process env fragment.
    def "openRound exposes the transport's decision-file path and the matching env fragment"() {
        given:
        def source = new HostRoundEnvironmentSource(
                new DecisionFileTransport(decisionRoot), new VirtualClock(), TestChildEnvironment.hostAllowlist())

        when:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))

        then:
        round.environment() instanceof HostTaskExecutionEnvironment
        round.decisionFilePath() != null
        round.decisionFilePath().startsWith(decisionRoot)
        round.decisionEnvFragment() == [GNOMISH_DECISION_FILE: round.decisionFilePath().toString()]

        cleanup:
        round.discard()
    }

    // FR21 of define-executor-contract: undecorated, the host source serves the git-less in-place
    // run, so it names the in-place identity — a round it opens yields no token and reads no git
    def "FR21: the bare host source names the in-place identity, which yields no token"() {
        given:
        def source = new HostRoundEnvironmentSource(
                new DecisionFileTransport(decisionRoot), new VirtualClock(), TestChildEnvironment.hostAllowlist())

        when:
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))

        then:
        source.identity().takeToken() == Optional.empty()

        cleanup:
        round.discard()
    }

    // FR5 of define-executor-contract (task 7.1): a host round's environment has a scratch area from
    // the open to the close or discard, where a program executor names its result files
    def "FR5: a round's environment has a scratch area that leaves at its #ending"() {
        given:
        def source = new HostRoundEnvironmentSource(
                new DecisionFileTransport(decisionRoot), new VirtualClock(), TestChildEnvironment.hostAllowlist())
        def round = source.openRound(StageExecutorRequests.request(workspaceDir))
        def scratch = Path.of(round.environment().scratchRoot())

        expect:
        Files.isDirectory(scratch)

        when:
        ending == 'close' ? round.closeRound() : round.discard()

        then:
        !Files.exists(scratch)

        cleanup:
        round.discard()

        where:
        ending << ['close', 'discard']
    }
}
