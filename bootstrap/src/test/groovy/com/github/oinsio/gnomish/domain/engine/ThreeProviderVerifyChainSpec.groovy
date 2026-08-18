package com.github.oinsio.gnomish.domain.engine

import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.GREEN_SHA
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.LOOPBACK_ALLOWLIST
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.QUALITY_GATE
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.RED_SHA
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.RUNS_PATH
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.WORKFLOW
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.actionsRun
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.TempDirCheckEnvironments
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The acceptance test of the whole change (M3, DEC-19): <em>one</em> stage runs a local {@code
 * command}, a SonarQube quality gate through the built-in {@code http} provider, and a GitHub
 * Actions run through the github plugin — three providers, one verify chain, one engine port.
 *
 * <p>Nothing about the check port is stood in for. The registry is the real {@code ServiceLoader}
 * pass, both operator subsections go through the real startup gate, the composite is the production
 * one, and each provider's client is built by its own discovered factory — including the http
 * client's egress allowlist, which is why the SonarQube stub speaks TLS on an address the operator
 * allowlisted literally (see {@code LoopbackTlsFixture}). What drives them is the engine's own
 * {@link VerifyOrchestrator} and {@link ExternalPolling} on a production clock, exactly as {@code
 * GiteaActionsStageVerifyE2ESpec} drives them for the live github case.
 *
 * <p>{@code ProviderDispatchingExternalCheckClientSpec} makes the same per-check-selection claim
 * over stand-in providers; this one makes it over the real ones, inside a verify chain.
 *
 * <p>Implements M3, FR6 of add-plugin-architecture.
 */
class ThreeProviderVerifyChainSpec extends Specification {

    private static final TaskContext CONTEXT = new TaskContext('ACC-1', 'title', 'body', [])
    private static final AttemptKey KEY = new AttemptKey('ACC-1', 'verify', 0)

    @Shared
    @TempDir
    Path tempDir

    @Shared
    ThreeProviderPlatformFixture platform = new ThreeProviderPlatformFixture()

    def setupSpec() {
        platform.start(tempDir)
    }

    def cleanupSpec() {
        platform.stop()
    }

    def setup() {
        platform.reset()
    }

    // M3, FR6: the headline claim. Three checks, three providers, one chain, every one green — and
    //     `size() == 3` explicitly, because the orchestrator stops at the first non-Pass, so a bare
    //     "all passed" would also hold of a chain that ran one check and gave up.
    def "one stage's verify chain runs a command check, a quality gate over http, and a GitHub Actions run"() {
        when:
        def result = verifyChain([
            command(),
            platform.qualityGate(),
            actionsRun()
        ], GREEN_SHA)

        then: 'all three checks ran, in manifest order, and all three passed'
        result.results.size() == 3
        result.results*.verdict.every { it instanceof Verdict.Pass }
        result.results*.checkRef*.label == [
            'command:true',
            'external:http:quality-gate',
            'external:github:' + WORKFLOW,
        ]
    }

    // FR6: "each resolved independently" — the two external checks were answered by two different
    //     providers' clients, visible as two different endpoints on two different listeners.
    def "each external check was answered by its own provider's client"() {
        given:
        def listener = new RecordingEventListener()

        when:
        verifyChain([
            command(),
            platform.qualityGate(),
            actionsRun()
        ], GREEN_SHA, listener)

        then: 'the http provider called the quality gate over TLS, the github provider the run list'
        platform.wireMock.verify(getRequestedFor(urlPathEqualTo(QUALITY_GATE)))
        platform.wireMock.verify(getRequestedFor(urlPathEqualTo(RUNS_PATH)))

        and: 'the engine bracketed each of the three checks with its own started/finished pair'
        listener.events.count { it instanceof EngineEvent.CheckStarted } == 3
        listener.events.count { it instanceof EngineEvent.CheckFinished } == 3
    }

    // NFR-S2, UX2: the operator's egress allowlist really is on the http leg — take the literal
    //     address out of it and that check alone becomes unverifiable, naming the missing entry,
    //     while the command check before it still passed on its own terms.
    def "the http leg goes through the operator's egress allowlist"() {
        when:
        def result = verifyChain(
                [
                    command(),
                    platform.qualityGate(),
                    actionsRun()
                ], GREEN_SHA, new RecordingEventListener(), [])

        then: 'the chain stopped at the refused http check, and the github check never ran'
        result.results.size() == 2
        result.results[0].verdict instanceof Verdict.Pass

        and:
        def refused = result.results[1].verdict
        refused instanceof Verdict.CannotVerify
        refused.reason().contains('factory.check.http.allowlist')
    }

    // FR6, and the "one chain" half of M3: a red run from one provider short-circuits the whole
    //     chain, so the three checks really are one verify list and not three independent runs.
    def "a red GitHub Actions run short-circuits the rest of the chain"() {
        when:
        def result = verifyChain([
            command(),
            actionsRun(),
            platform.qualityGate()
        ], RED_SHA)

        then: 'the failing github check ended the chain before the http check was reached'
        result.results.size() == 2
        def failed = result.results[1].verdict
        failed instanceof Verdict.Fail
        failed.findings()*.message().join(' ').contains('build')
        platform.wireMock.verify(0, getRequestedFor(urlPathEqualTo(QUALITY_GATE)))
    }

    /** The engine's own verify chain, on a production clock and sleeper, over one attempt commit. */
    private VerificationResult verifyChain(
            List<VerifyCheck> checks,
            String sha,
            RecordingEventListener listener = new RecordingEventListener(),
            List<String> allowlist = LOOPBACK_ALLOWLIST) {
        def clock = new SystemClock()
        def polling = new ExternalPolling(
                platform.checkClient(allowlist), AttemptDelivery.assumedDelivered(), clock, new ThreadSleeper())
        def orchestrator = new VerifyOrchestrator(
                new ScriptedBuiltinCheckRunner(),
                new ShellCommandCheckRunner().withEnvironments(new TempDirCheckEnvironments(tempDir, clock)),
                polling,
                new JudgeVoting(new ScriptedJudgeVoter()),
                clock,
                listener)
        def ref = new AttemptCommitRef()
        ref.record(sha)
        orchestrator.verify(checks, CONTEXT, new RecordedAttemptCommitWorkspace(ref), KEY)
    }

    private static VerifyCheck.Command command() {
        new VerifyCheck.Command('true')
    }
}
