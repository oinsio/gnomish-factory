package com.github.oinsio.gnomish.app.serve

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.adapter.tracker.github.GithubFeedQuery
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import spock.lang.Specification

/**
 * NFR-R3, "Daemon tolerates tracker outages": drives the REAL {@link GithubFeedQuery} (the
 * production {@code listReady} path) against a WireMock server that fails every request with a
 * connection reset — exhausting the adapter's OWN Resilience4j retry budget (see {@code
 * GithubRetryConfigSpec}/{@code GithubHttpClientSpec}: a persistent connection failure, unlike a
 * persistent 5xx, throws {@code GithubHttpException} once retries are exhausted) — for a short
 * window, then recovers. A {@link FeedAutomaton} polling through this tracker must not crash: the
 * outage is retried at the {@link FeedCycle}/{@link FeedOutageRetry} layer (WARN + backoff) rather
 * than propagating, and the automaton claims normally the moment WireMock starts answering again.
 *
 * <p>Uses a maxAttempts(1) retry config so each {@code listReady} call the feed makes maps to
 * exactly one HTTP request, keeping the WireMock scenario simple: two failed HTTP calls (the
 * outage window), a third that finally succeeds. The outage-retry backoff itself runs on a {@link
 * VirtualSleeper}, so the whole "outage window" costs no real wall-clock time beyond the two fast
 * in-process HTTP calls to WireMock (NFR-R3's "must run fast in CI" concern).
 *
 * <p>Implements NFR-R3 of add-factory-serve.
 */
class FeedAutomatonOutageIntegrationSpec extends Specification {

    private static final String OWNER = 'acme'
    private static final String REPO = 'widgets'
    private static final String READY_LABEL = 'gnomish:ready'
    private static final InstanceId INSTANCE = InstanceId.generate('gnome')

    private WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    // One HTTP attempt per feed-level poll: no internal client retry, so the WireMock scenario's
    // fault count exactly matches the number of poll-level failures the feed itself must absorb.
    // The exception predicate matches everything rather than naming the adapter's package-private
    // GithubHttpUncheckedIOException (illegal cross-package access from this spec's package) —
    // harmless here since maxAttempts(1) never actually retries regardless of the predicate.
    private static RetryConfig noInternalRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(1)
                .intervalFunction(IntervalFunction.of(1))
                .retryOnException({ Throwable t -> true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    def "the feed survives a sustained tracker outage and resumes claiming once WireMock recovers"() {
        given: 'the List Issues endpoint resets the connection twice, then recovers with one ready task'
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .inScenario('outage')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo('failed-once'))
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .inScenario('outage')
                .whenScenarioStateIs('failed-once')
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo('recovered'))
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .inScenario('outage')
                .whenScenarioStateIs('recovered')
                .willReturn(aResponse().withStatus(200).withBody('[{"number":42}]')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/42/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))

        and: 'a FeedAutomaton whose listReady goes through the real GithubFeedQuery over WireMock'
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'outage-test-token', noInternalRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        def feedQuery = new GithubFeedQuery(cache, OWNER, REPO, READY_LABEL)
        def claimed = new CopyOnWriteArrayList<TaskRef>()
        Tracker tracker = [
            listReady: { int limit -> feedQuery.listReady(limit) },
            listOpen : { -> [] },
            claim    : { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker
        def ledger = new SlotLedger(1)
        def slotRunner = { TaskRef ref -> claimed.add(ref) } as SlotRunner
        def clock = new VirtualClock()
        def sleeper = new VirtualSleeper(clock)
        def automaton = new FeedAutomaton(
                tracker, INSTANCE, ledger, slotRunner, sleeper, clock,
                Duration.ofMinutes(2), Duration.ofHours(1), Duration.ofSeconds(30), 2, new Random(1))

        when: 'the automaton polls through the outage window with no real wall-clock wait'
        def state = automaton.step()

        then: 'the outage never escaped as an exception; two backoff pauses covered the two failed attempts'
        state == FeedState.FILLING
        sleeper.slept.size() == 2

        and: 'the feed is fully functional again: it claimed the task that appeared once the tracker recovered'
        claimed.collect { it.id() } == [
            'github:localhost/acme/widgets#42'
        ]

        and: 'all three HTTP attempts actually reached WireMock (two faulted, one succeeded)'
        wireMock.verify(3, getRequestedFor(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100')))
    }

    // Sanity companion: absent stubbed candidates, the same outage-tolerant tracker still leaves
    // the automaton in a valid idle state rather than crashing — the WireMock outage window
    // resolves to an ordinary empty poll, not a special "outage" feed state (design note in the
    // task: no new FeedState is invented for tracker health).
    def "an outage resolving to an empty feed lands in Idle-empty, not a crash"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .inScenario('empty-outage')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo('recovered'))
        wireMock.stubFor(get(urlEqualTo(
                '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'))
                .inScenario('empty-outage')
                .whenScenarioStateIs('recovered')
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'outage-test-token', noInternalRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        def feedQuery = new GithubFeedQuery(cache, OWNER, REPO, READY_LABEL)
        Tracker tracker = [
            listReady: { int limit -> feedQuery.listReady(limit) },
            listOpen : { -> [] },
        ] as Tracker
        def ledger = new SlotLedger(1)
        def clock = new VirtualClock()
        def sleeper = new VirtualSleeper(clock)
        def automaton = new FeedAutomaton(
                tracker, INSTANCE, ledger, { TaskRef ref -> } as SlotRunner, sleeper, clock,
                Duration.ofMinutes(2), Duration.ofHours(1), Duration.ofSeconds(30), 2, new Random(1))

        when:
        def state = automaton.step()

        then:
        state == FeedState.IDLE_EMPTY
        sleeper.slept.size() == 2 // one outage-retry backoff, plus the ordinary Idle-empty sleep
    }
}
