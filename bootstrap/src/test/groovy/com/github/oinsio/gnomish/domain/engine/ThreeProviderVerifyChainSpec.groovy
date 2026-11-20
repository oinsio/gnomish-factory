package com.github.oinsio.gnomish.domain.engine

import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.GREEN_RUN
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.LOOPBACK_ALLOWLIST
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.QUALITY_GATE
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.RUNS_PATH
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.WORKFLOW
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.actionsRun
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.runPage
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.TempDirCheckEnvironments
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.app.workspace.fake.ClosedRounds
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * One stage's verify chain over three built-in executors (M3 of add-plugin-architecture, carried onto
 * the executor contract by task 4.2f of migrate-verify-to-executor-contract): a local {@code shell}
 * command, a SonarQube quality gate through the {@code http} built-in, and a GitHub Actions run through
 * the {@code github-actions} built-in — three executors, one verify chain, one engine port.
 *
 * <p>Nothing about the check rounds is stood in for: the registry holds the built-ins' entries, the
 * {@code github-actions} connection is admitted from the operator's subsection, the check dispatch
 * binds each check behind the pin-check of a real law commit, and the {@code http} judgement goes
 * through the operator's {@code factory.egress.in-process} scope — which is why the SonarQube stub
 * speaks TLS on a loopback address its {@code loopback} class word grants (see {@code
 * LoopbackTlsFixture}). What drives them is the engine's own {@link VerifyOrchestrator} and {@link
 * PendingPoll} on virtual time, exactly as {@code GiteaActionsStageVerifyE2ESpec} drives them for the
 * live case.
 *
 * <p>Implements M3, FR6 of add-plugin-architecture; FR1, FR2, FR6 of
 * migrate-verify-to-executor-contract (design D23).
 */
class ThreeProviderVerifyChainSpec extends Specification {

    private static final TaskContext CONTEXT = new TaskContext('ACC-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
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
        ], platform.greenSha)

        then: 'all three checks ran, in manifest order, and all three passed'
        result.results.size() == 3
        result.results*.verdict.every { it instanceof Verdict.Pass }
        result.results*.checkRef*.label*.forLog() == [
            'command:true',
            'external:http:quality-gate',
            'external:github-actions:' + WORKFLOW,
        ]
    }

    // Scenario: verification/github-external-check / A green check is auditable from the tracker
    // FR6, D23 (the D9 row's identity spec): each built-in's pass carries its run URL across the
    //     contract — the run's html_url and the http target — into the recorded Verdict.Pass.
    def "a green external check records its platform run URL with the pass"() {
        when:
        def result = verifyChain([
            platform.qualityGate(),
            actionsRun()
        ], platform.greenSha)

        then:
        result.results*.verdict.every { it instanceof Verdict.Pass }
        result.results*.verdict*.runUrl() == [
            platform.qualityGate().settings().url,
            runPage(GREEN_RUN)
        ]
    }

    // FR6: "each resolved independently" — the two external checks were answered by two different
    //     built-ins, visible as two different endpoints on two different listeners.
    def "each external check was answered by its own provider's client"() {
        given:
        def listener = new RecordingEventListener()

        when:
        verifyChain([
            command(),
            platform.qualityGate(),
            actionsRun()
        ], platform.greenSha, listener)

        then: 'the http built-in called the quality gate over TLS, the github-actions built-in the run list'
        platform.wireMock.verify(getRequestedFor(urlPathEqualTo(QUALITY_GATE)))
        platform.wireMock.verify(getRequestedFor(urlPathEqualTo(RUNS_PATH)))

        and: 'the engine bracketed each of the three checks with its own started/finished pair'
        listener.events.count { it instanceof EngineEvent.CheckStarted } == 3
        listener.events.count { it instanceof EngineEvent.CheckFinished } == 3
    }

    // NFR-S2, UX2: the operator's egress allowlist really is on the http leg — take the class word
    //     out of the in-process scope and that check alone becomes unverifiable, naming the scope,
    //     while the command check before it still passed on its own terms.
    def "the http leg goes through the operator's egress allowlist"() {
        when:
        def result = verifyChain(
                [
                    command(),
                    platform.qualityGate(),
                    actionsRun()
                ], platform.greenSha, new RecordingEventListener(), [])

        then: 'the chain stopped at the refused http check, and the github check never ran'
        result.results.size() == 2
        result.results[0].verdict instanceof Verdict.Pass

        and:
        def refused = result.results[1].verdict
        refused instanceof Verdict.CannotVerify
        refused.reason().contains('factory.egress.in-process')
    }

    // FR6, and the "one chain" half of M3: a red run from one executor short-circuits the whole
    //     chain, so the three checks really are one verify list and not three independent runs.
    def "a red GitHub Actions run short-circuits the rest of the chain"() {
        when:
        def result = verifyChain([
            command(),
            actionsRun(),
            platform.qualityGate()
        ], platform.redSha)

        then: 'the failing github check ended the chain before the http check was reached'
        result.results.size() == 2
        def failed = result.results[1].verdict
        failed instanceof Verdict.Fail
        failed.findings()*.message().join(' ').contains('build')
        platform.wireMock.verify(0, getRequestedFor(urlPathEqualTo(QUALITY_GATE)))
    }

    /** The engine's own verify chain, on one virtual time equipment, over one attempt commit. */
    private VerificationResult verifyChain(
            List<VerifyCheck> checks,
            String sha,
            RecordingEventListener listener = new RecordingEventListener(),
            List<String> allowlist = LOOPBACK_ALLOWLIST) {
        // FR21 of supervise-daemon-loops-and-embed-dashboard: one virtual equipment for the whole
        //     chain; a poll's wait advances it instead of passing real time.
        def time = VirtualTimeEquipment.create()
        def clock = time.clock()
        def shell = new ShellCommandCheckRunner(clock).withEnvironments(new TempDirCheckEnvironments(tempDir, clock))
        def rounds = platform.checkRounds(checks, allowlist, shell, clock)
        def orchestrator = new VerifyOrchestrator(
                new PendingPoll(rounds, new InMemoryAttemptPersistence(), AttemptDelivery.assumedDelivered(), time,
                listener),
                new JudgeVoting(new ScriptedJudgeVoter()),
                clock,
                listener)
        def ref = ClosedRounds.at(sha)
        orchestrator.verify(VerifyingStages.of(checks), CONTEXT, new RecordedAttemptCommitWorkspace(ref), KEY, TaskState.atStageStart('build'), null)
    }

    private static VerifyCheck.Executor command() {
        VerifyChecks.command('true')
    }
}
