package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.SHA
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.run
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.status
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR4, NFR-R2 of migrate-verify-to-executor-contract, design D3, D16: what the {@code scm-status}
 * built-in answers when it cannot read a verdict — a request it does not serve and a key that is no
 * commit are refused before any call, a read the SCM could not answer is {@code
 * failed/infrastructure} naming why, and a rate-limit answer keeps the run pending on the same key.
 */
class ScmStatusUnansweredSpec extends Specification {

    WireMockServer scm = new WireMockServer(0)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    def "a request it cannot answer is refused before any call: #why"() {
        given:
        def executor = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build'])

        when:
        def results = [
            executor.start(request),
            executor.poll(request, new PendingKey(SHA))
        ]

        then:
        results.every {
            it.status() == Status.FAILED && it.failureClass() == FailureClass.INFRASTRUCTURE && it.reason().contains(reason)
        }
        scm.allServeEvents.isEmpty()

        where:
        why | request || reason
        'another role' | ScmStatusRig.request(Role.TRANSFORM, RoleInputs.transform('do it')) || 'answers verdict runs only, and was asked for a transform run'
        'settings' | ScmStatusRig.verdict([expected: ['build']]) || 'takes no settings, and was given 1'
    }

    def "a working copy that names no attempt commit, or a key that is no commit, is refused before any call"() {
        given:
        def reader = ScmStatusRig.connected(scm)

        when:
        def noCommit = new ScmStatusExecutor(reader, ScmStatusRig.check(['build']), new Workspace() {}).start(ScmStatusRig.verdict())
        def badKey = ScmStatusRig.executor(reader, ['build']).poll(ScmStatusRig.verdict(), new PendingKey('main'))

        then:
        noCommit.failureClass() == FailureClass.INFRASTRUCTURE
        noCommit.reason().contains('reads the attempt commit from its working copy')
        badKey.failureClass() == FailureClass.INFRASTRUCTURE
        badKey.reason().forLog() == "scm-status reads not a commit object name: 'main'"
        scm.allServeEvents.isEmpty()
    }

    def "an answer that is no listing is failed/infrastructure naming it: #why"() {
        given:
        scm.stubFor(get(urlEqualTo(ScmStatusRig.statusPath())).willReturn(answer))
        ScmStatusRig.noChecksApi(scm)

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.fastRetrying(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains(reason)

        where:
        why | answer || reason
        'a rejected token' | aResponse().withStatus(401) || 'combined status listing with HTTP 401'
        'a persistent server error' | aResponse().withStatus(503) || 'combined status listing with HTTP 503'
        'malformed JSON' | aResponse().withStatus(200).withBody('{') || 'combined status listing with malformed JSON'
        'a JSON array' | aResponse().withStatus(200).withBody('[]') || 'combined status listing with no JSON object'
        'a dropped connection' | aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER) || 'GitHub API call failed after retries'
        'a redirect off the admitted set' | aResponse().withStatus(302).withHeader('Location', 'http://169.254.169.254/latest') || 'refused by the factory egress guard (missing allowlist entry, factory.check.scm-status.api-url)'
        'a redirect loop' | aResponse().withStatus(302).withHeader('Location', ScmStatusRig.statusPath()) || 'redirects back to a URL this chain already visited'
    }

    def "a check-runs listing that is neither a listing nor absent is failed/infrastructure"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')])
        scm.stubFor(get(urlEqualTo(ScmStatusRig.checkRunsPath())).willReturn(aResponse().withStatus(403)))

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().forLog() == 'the SCM answered the check runs listing with HTTP 403'
    }

    // NFR-R2: a rate-limit answer the core returned keeps the run pending on the same key, its reason
    //     saying so; its pending state and wait hint are ScmStatusPendingStateSpec's, and the
    //     scenario "Rate limit backs off" takes its marker with the engine's half (task 4.2)
    def "a rate-limit answer on #listing is pending on the attempt SHA, its reason naming the limit"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')])
        ScmStatusRig.noChecksApi(scm)
        scm.stubFor(get(urlEqualTo(path)).willReturn(answer))

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.fastRetrying(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.pendingKey() == new PendingKey(SHA)
        result.reason().forLog() == "the SCM refused the read for its rate limit (HTTP ${code}); the read backs off until the check's next poll"

        where:
        listing | path | answer || code
        'the combined status, primary limit' | ScmStatusRig.statusPath() | aResponse().withStatus(403).withHeader('x-ratelimit-remaining', '0') || 403
        'the combined status, secondary limit' | ScmStatusRig.statusPath() | aResponse().withStatus(429).withHeader('retry-after', '60') || 429
        'the check runs' | ScmStatusRig.checkRunsPath() | aResponse().withStatus(429) || 429
    }
}
