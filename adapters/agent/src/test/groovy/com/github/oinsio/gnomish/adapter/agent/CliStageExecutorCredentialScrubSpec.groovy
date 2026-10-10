package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * D17, NFR-S1 of add-tracker-port; FR9, D6 of add-sandbox-core: {@link CliStageExecutor}'s
 * allowlist constructor threads the {@link ChildEnvAllowlist} into the {@code
 * HostTaskExecutionEnvironment} it runs each round through, which composes the child environment
 * as base ∪ passthrough ∪ factory-set with declared credential names excluded — a focused
 * unit-level proof, one rung below {@code TakeCommandCredentialScrubSpec}'s full {@code
 * take}-flavored end-to-end wiring proof. HOME doubles as the observable credential: it sits in
 * the host base set, so its absence can only come from the credential exclusion.
 */
class CliStageExecutorCredentialScrubSpec extends Specification {

    private static final String CREDENTIAL_VAR = 'HOME'

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    /** Where the agent stand-in's per-run link and its record live — apart from the workspace. */
    @TempDir
    Path standInDir

    private Path agentStandIn

    /**
     * The committed {@code agent-reporting-home} stand-in, through a per-run link: it
     * records whether {@code CREDENTIAL_VAR} reached it, then plays plain-round (ADR 0015).
     */
    private FactoryProperties wrapperReporting() {
        assert CREDENTIAL_VAR == 'HOME': 'the reporting preset records HOME'
        agentStandIn = StandIn.link(standInDir.resolve('plain-round'), 'agent-reporting-home')
        new FactoryProperties('factory-01', agentStandIn.toString(), null, null)
    }

    /** What the agent saw of {@code CREDENTIAL_VAR}, one entry per spawned round: present or absent. */
    private List<String> credentialReports() {
        StandInLog.blocks(agentStandIn).collect {
            it[CREDENTIAL_VAR] == 'unset' ? 'absent' : 'present'
        }
    }

    // Delegates to FakeAgentSupport#requestFor, the single owner of this fixture shape.
    private static StageExecutor.Request requestFor(Path workspaceDir) {
        FakeAgentSupport.requestFor(workspaceDir)
    }

    def "a declared credential in the allowlist never reaches the spawned process"() {
        given:
        def executor = new CliStageExecutor(
                wrapperReporting(),
                clock,
                { event -> } as AgentProgressListener,
                ChildEnvAllowlist.of([], [CREDENTIAL_VAR]),
                LAW)

        when:
        executor.execute(requestFor(workspaceDir))

        then:
        credentialReports() == ['absent']
    }

    def "with nothing declared, the same base variable reaches the spawned process"() {
        given:
        def executor = new CliStageExecutor(
                wrapperReporting(), clock, { event -> } as AgentProgressListener, ChildEnvAllowlist.none(), LAW)

        when:
        executor.execute(requestFor(workspaceDir))

        then:
        credentialReports() == ['present']
    }
}
