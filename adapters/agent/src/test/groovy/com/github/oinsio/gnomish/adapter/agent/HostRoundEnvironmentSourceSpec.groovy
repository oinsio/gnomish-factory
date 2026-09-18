package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
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
                new DecisionFileTransport(decisionRoot), new VirtualClock(), ChildEnvAllowlist.none())

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
}
