package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.SHA
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.run
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.status
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.PendingState
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.tomakehurst.wiremock.WireMockServer
import java.time.Duration
import spock.lang.Specification

/**
 * FR2, FR4, FR9, NFR-R2, UX1 of migrate-verify-to-executor-contract (design D19): a pending {@code
 * scm-status} answer carries the typed facts the engine decides on — {@code unreported} while an
 * expected context is absent on the attempt SHA, {@code running} once every one has reported and
 * one still runs, {@code unobserved} with a wait hint for a rate limit — and its reason still names
 * what is awaited.
 */
class ScmStatusPendingStateSpec extends Specification {

    WireMockServer scm = new WireMockServer(0)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    // Scenario: verification/verdict-executors / Absent context is reported as unreported
    def "an expected context absent on the attempt SHA makes the answer unreported, #why"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', state)])
        ScmStatusRig.noChecksApi(scm)

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'lint']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.pendingKey() == new PendingKey(SHA)
        result.pendingState() == PendingState.UNREPORTED
        result.pollAfterMillis() == null
        result.reason().forLog() == reason

        where:
        why | state || reason
        'the other green' | 'success' || "waiting on 'lint' (absent)"
        'the other still running' | 'pending' || "waiting on 'build' (running), 'lint' (absent)"
    }

    // Scenario: verification/verdict-executors / Running context is reported as running
    def "every expected context reported, one still running, makes the answer running"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')])
        ScmStatusRig.checkRuns(scm, [
            run(7, 'test', 'in_progress', null)
        ])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'test']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.pendingState() == PendingState.RUNNING
        result.pollAfterMillis() == null
        result.reason().forLog() == "waiting on 'test' (running)"
    }

    // Executor half of "Rate limit backs off" (marker in task 4.2, with the engine's half).
    def "a rate-limited read is pending unobserved, its wait hint #why"() {
        given:
        scm.stubFor(get(urlEqualTo(ScmStatusRig.statusPath())).willReturn(answer))

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.fastRetrying(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.pendingKey() == new PendingKey(SHA)
        result.pendingState() == PendingState.UNOBSERVED
        result.pollAfterMillis() == hint
        result.reason().forLog() == "the SCM refused the read for its rate limit (HTTP ${code}); the read backs off until the check's next poll"

        and: 'the core returned the long pause at once, without a retry'
        scm.verify(1, getRequestedFor(urlEqualTo(ScmStatusRig.statusPath())))

        where:
        why | answer || code | hint
        "from retry-after" | aResponse().withStatus(429).withHeader('retry-after', '60') || 429 | 60_000L
        "from the reset against Date" | aResponse().withStatus(403).withHeader('x-ratelimit-remaining', '0')
                .withHeader('x-ratelimit-reset', '1794477900').withHeader('Date', 'Thu, 12 Nov 2026 10:00:00 GMT') || 403 | 300_000L
    }

    def "the wait hint of a pause of #pause is #hint: clamped to one day, never below zero"() {
        when:
        ExecutorResult result = ScmStatusResults.rateLimited(AttemptSha.of(SHA), new ScmStatusRead.RateLimited(429, pause))

        then:
        result.pollAfterMillis() == hint
        result.pendingState() == PendingState.UNOBSERVED

        where:
        pause || hint
        Duration.ofSeconds(90_000) || ExecutorResult.MAX_POLL_AFTER_MILLIS
        Duration.ofMillis(ExecutorResult.MAX_POLL_AFTER_MILLIS) || ExecutorResult.MAX_POLL_AFTER_MILLIS
        Duration.ofSeconds(Long.MAX_VALUE) || ExecutorResult.MAX_POLL_AFTER_MILLIS
        Duration.ZERO || 0L
    }
}
