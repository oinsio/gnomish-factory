package com.github.oinsio.gnomish.adapter.scm.actions

import static com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsRig.LOG
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.githubapi.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR15, FR17 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09: a red run's
 * job log reaches the {@code github-actions} finding over the real guarded exchange — read as a
 * bounded tail past the GitHub core's response bound, and through a redirect onto another admitted
 * host — while a redirect onto a host no admitted set names fails the check as infrastructure,
 * before any connection to it.
 */
class GithubActionsLogEgressSpec extends Specification {

    WireMockServer platform = new WireMockServer(0)

    def setup() {
        platform.start()
        GithubActionsRig.run(platform, 'completed', 'failure')
        GithubActionsRig.failedBuildJob(platform)
    }

    def cleanup() {
        platform.stop()
    }

    // FR15, D16: the core reads a job log as a tail bounded by its own 16 MiB
    // Scenario: verification/factory-egress-allowlist / A job log is read as a bounded tail
    def "a job log larger than the core's response bound reaches the finding as its tail, and the check a verdict"() {
        given: 'a log one MiB past the bound, whose last line is the failure'
        def log = ('x' * (GithubHttpClient.MAX_BODY_BYTES + 1024 * 1024)) + '\nboom: assertion failed'
        platform.stubFor(get(urlEqualTo(LOG)).willReturn(aResponse().withStatus(200).withBody(log)))

        when:
        def result = GithubActionsRig.executor(platform).start(GithubActionsRig.verdict())

        then: 'a verdict, not cannot-verify, carrying the tail'
        result.status() == Status.COMPLETED
        def results = GithubActionsRig.results(result)
        results.size() == 1
        results[0].details().contains('boom: assertion failed')
    }

    def "a job-log redirect onto another admitted host reaches the finding"() {
        given: 'the platform redirects the log onto its store, another host of the admitted set'
        def store = platform.baseUrl().replace('localhost', '127.0.0.1') + '/store/10.log'
        platform.stubFor(get(urlEqualTo(LOG)).willReturn(aResponse().withStatus(302).withHeader('Location', store)))
        platform.stubFor(get(urlEqualTo('/store/10.log')).willReturn(aResponse().withStatus(200).withBody('stored log tail')))

        when:
        def result = GithubActionsRig.executor(platform).start(GithubActionsRig.verdict())

        then:
        result.status() == Status.COMPLETED
        GithubActionsRig.results(result)[0].details().contains('stored log tail')
    }

    // FR15, FR17, D16 (the per-layer table): a verdict built-in's refusal is failed/infrastructure
    // Scenario: verification/verdict-executors / A built-in's HTTP call outside its declared hosts is refused
    def "a redirect onto a host no admitted set names fails the check as infrastructure, naming the host and the key"() {
        given: 'a public address, which neither the describe, the connection nor the in-process scope names'
        platform.stubFor(get(urlEqualTo(LOG)).willReturn(aResponse().withStatus(302)
                .withHeader('Location', 'http://93.184.216.34/logs/10')))

        when:
        def result = GithubActionsRig.executor(platform).start(GithubActionsRig.verdict())

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('93.184.216.34')
        result.reason().contains('factory.check.github-actions')
    }
}
