package com.github.oinsio.gnomish.domain.engine

import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.LOOPBACK_ALLOWLIST
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.STUCK_QUALITY_GATE
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.app.workspace.fake.ClosedRounds
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyingStages
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The timeout of a check served by the {@code http} built-in, driven to its deadline end to end: the
 * timeout and its {@code timeoutClass} live in the engine's {@link PendingPoll}, so no executor
 * implements them — but until a real http check actually reaches the deadline, that sharing is an
 * architectural inference rather than an observation.
 *
 * <p>Everything below the check is production: the built-ins' registry entries, the real http client
 * under its egress scope, the check dispatch, {@link PendingPoll} on virtual time (see {@link
 * ThreeProviderPlatformFixture}). The stub answers {@code IN_PROGRESS} forever, so every round is
 * pending and the wait can only end at the deadline. {@code ThreeProviderVerifyChainSpec} covers the
 * same built-in's green, refused and short-circuiting paths inside a verify chain.
 *
 * <p>Implements FR9 of add-external-check-github-actions; FR6 of add-plugin-architecture; FR2, FR4
 * of migrate-verify-to-executor-contract.
 */
class HttpCheckTimeoutClassSpec extends Specification {

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

    // FR9: a stuck http check burns an attempt as a quality Fail by default, and escalates as
    //     CannotVerify when the operator declared the timeout infrastructural — the same two
    //     classifications every executor's checks get, from the one pending poll.
    def "a stuck http check times out and classifies per its declared timeoutClass"() {
        given:
        def check = platform.stuckQualityGate(timeoutClass)
        // FR21 of supervise-daemon-loops-and-embed-dashboard: the timeout elapses on virtual time,
        //     each wait advancing the clock.
        def time = VirtualTimeEquipment.create()
        def rounds = platform.checkRounds([check], LOOPBACK_ALLOWLIST, new ShellCommandCheckRunner(time.clock()), time.clock())
        def poll = new PendingPoll(rounds, new InMemoryAttemptPersistence(), AttemptDelivery.assumedDelivered(), time,
                new RecordingEventListener())
        def workspace = new RecordedAttemptCommitWorkspace(ClosedRounds.at(platform.greenSha))
        def round = new PendingRound(VerifyingStages.of([check]), new TaskContext('TO-1', UntrustedText.tracker('t'),
        UntrustedText.tracker('b'), []), workspace, new AttemptKey('TO-1', 'verify', 0), TaskState.atStageStart('verify'), null)

        when:
        def verdict = poll.answer(check, DescribedExecutors.none().executorOf(check), CheckRef.of(0, check), round).answer()

        then: 'the wait really polled the stuck gate over TLS before resolving on the deadline'
        platform.wireMock.verify(moreThanOrExactly(1), getRequestedFor(urlPathEqualTo(STUCK_QUALITY_GATE)))

        and: 'the verdict is the class the check declared, naming the check and its elapsed timeout'
        expectedVerdict.isInstance(verdict)
        reasonOf(verdict).contains(check.checkId())
        reasonOf(verdict).contains(check.timeout().toString())

        where:
        timeoutClass || expectedVerdict
        VerifyCheck.TimeoutClass.QUALITY || VerdictAnswer.QualityFailure
        VerifyCheck.TimeoutClass.INFRASTRUCTURE || VerdictAnswer.InfrastructureFailure
    }

    /**
     * The one message each timeout answer carries, whichever class it resolved to.
     */
    private static String reasonOf(VerdictAnswer answer) {
        answer instanceof VerdictAnswer.QualityFailure
                ? answer.findings()*.message().join(' ')
                : answer.reason().forLog()
    }
}
