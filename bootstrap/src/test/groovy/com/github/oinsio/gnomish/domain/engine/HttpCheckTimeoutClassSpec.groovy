package com.github.oinsio.gnomish.domain.engine

import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.LOOPBACK_ALLOWLIST
import static com.github.oinsio.gnomish.domain.engine.ThreeProviderPlatformFixture.STUCK_QUALITY_GATE
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The poll timeout of a check served by the built-in {@code http} provider, driven to its deadline
 * end to end: the timeout and its {@code timeoutClass} live in the engine's shared {@link
 * ExternalPolling}, so nothing per-provider implements them — but until a real http check actually
 * reaches the deadline, that sharing is an architectural inference rather than an observation.
 *
 * <p>Everything below the check is production: the discovered registry, the real http client with
 * its egress allowlist, {@link ExternalPolling} on a production clock and sleeper (see {@link
 * ThreeProviderPlatformFixture}). The stub answers {@code IN_PROGRESS} forever, so the loop can only
 * resolve by timing out. {@code ThreeProviderVerifyChainSpec} covers the same provider's green,
 * refused and short-circuiting paths inside a verify chain.
 *
 * <p>Implements FR9 of add-external-check-github-actions; FR6 of add-plugin-architecture.
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
    //     classifications the github provider's checks get, from the same shared poll loop.
    def "a stuck http check times out and classifies per its declared timeoutClass"() {
        given:
        def check = platform.stuckQualityGate(timeoutClass)
        def polling = new ExternalPolling(
                platform.checkClient(LOOPBACK_ALLOWLIST),
                AttemptDelivery.assumedDelivered(),
                new SystemClock(),
                new ThreadSleeper())
        def ref = new AttemptCommitRef()
        ref.record(ThreeProviderPlatformFixture.GREEN_SHA)

        when:
        def verdict = polling.poll(check, new AttemptCommitWorkspace(ref))

        then: 'the loop really polled the stuck gate over TLS before resolving on the deadline'
        platform.wireMock.verify(moreThanOrExactly(1), getRequestedFor(urlPathEqualTo(STUCK_QUALITY_GATE)))

        and: 'the verdict is the class the check declared, naming the check and its elapsed timeout'
        expectedVerdict.isInstance(verdict)
        reasonOf(verdict).contains(check.checkId())
        reasonOf(verdict).contains(check.timeout().toString())

        where:
        timeoutClass || expectedVerdict
        VerifyCheck.TimeoutClass.QUALITY || Verdict.Fail
        VerifyCheck.TimeoutClass.INFRASTRUCTURE || Verdict.CannotVerify
    }

    /** The one message each timeout verdict carries, whichever class it resolved to. */
    private static String reasonOf(Verdict verdict) {
        verdict instanceof Verdict.Fail ? verdict.findings()*.message().join(' ') : verdict.reason()
    }
}
