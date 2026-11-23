package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.runner.AgentRoundRunner
import com.github.oinsio.gnomish.adapter.agent.runner.StdoutCaps
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Timeout

/**
 * The credential row of design D8 of extract-agent-round-runner (NFR-S1), on a real box — the
 * {@code claude} counterpart of {@link ProgramCredentialDeliverySpec}: the credentials the Claude
 * dialect's describe declares reach the agent CLI's child through the run allowlist's grant, the
 * one a {@code program} executor's credentials take, each with the value the source resolved, a
 * declared name with no value is left unset, and a sibling exec in the same box receives none of
 * them. The round is a real {@link AgentRoundRunner} round over the real {@link ClaudeDialect}; the
 * box's agent binary is the credential-capturing stand-in, which records what its process received
 * in the working copy, read back from the box.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ClaudeCredentialDeliverySpec extends CredentialDeliverySpecBase {

    static final String OAUTH_VALUE = 'oauth-delivered-value'

    /** The secrets port's stand-in: the CLI's subscription token resolves, its API key does not. */
    static final ChildEnvValues CLAUDE_SECRETS = { String name ->
        Optional.ofNullable([CLAUDE_CODE_OAUTH_TOKEN: OAUTH_VALUE, ACME_API_KEY: VALUE][name])
    } as ChildEnvValues

    def "NFR-S1, D8: the credentials the Claude describe declares reach the agent child alone, with the source's values"() {
        given: 'a box whose agent binary records its credentials, and the dialect of a run over the allowlist'
        def installation = ChildEnvAllowlist.of([], [], CLAUDE_SECRETS)
        def support = startTask(ProgramInBox.law(), installation, FakeAgentSandboxImage.ensureBuiltCapturingCredentials())
        def box = support.lease().environmentFor('work')
        def dialect = ClaudeDialect.of(FakeAgentSandboxImage.CREDENTIAL_CAPTURING_BINARY, UntrustedText.subprocess('fake'),
                installation)
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, ProgramInBox.realTime(), installation.programGrant(),
                Files.createDirectories(tempDir.resolve('stderr')), LastRegisteredCommit.NONE)

        when: 'a real runner round runs the dialect in the box; a sibling exec carries nothing of its own'
        def result = new AgentRoundRunner<>('claude', dialect, box, equipment, { event -> } as AgentProgressListener,
        StdoutCaps.DEFAULTS, null).start(ProgramInBox.request())
        def captured = output(box, [
            'cat',
            FakeAgentSandboxImage.CREDENTIAL_CAPTURE
        ], [:]).readLines()
        def sibling = names(box, [:])

        then: 'both captured names are ones the describe declares'
        result.status() == Status.COMPLETED
        dialect.describe().credentials().containsAll([
            'CLAUDE_CODE_OAUTH_TOKEN',
            'ANTHROPIC_API_KEY'
        ])

        and: 'the resolvable one arrived with the value the source resolved, the other stayed unset'
        captured.contains('CLAUDE_CODE_OAUTH_TOKEN=' + OAUTH_VALUE)
        captured.contains('ANTHROPIC_API_KEY=')

        and: 'a sibling exec in the same box receives none of them'
        !sibling.contains('CLAUDE_CODE_OAUTH_TOKEN')
        !sibling.contains('ANTHROPIC_API_KEY')
    }
}
