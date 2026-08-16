package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * Regression proof for the "Token never leaks into findings" scenario of the "Token resolution
 * and hygiene" requirement (github-external-check spec): a {@link GithubWorkflowRunPoll} that
 * fails with {@link PollStatus.CannotVerify} never lets the raw token value — sent only as the
 * {@code Authorization: Bearer <token>} header by {@link GithubHttpClient#send} — reach the
 * {@code reason()}/{@code details()} strings the tracker report renders. Both {@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpException} and {@link
 * GithubWorkflowRunInfrastructureException} build their messages from the request URI or the
 * response status code only, never from the request object itself, so this is a regression proof
 * rather than a fix.
 *
 * <p>Implements FR8, NFR-S1 of add-external-check-github-actions.
 */
class GithubTokenHygieneSpec extends Specification {

    private static final String SECRET_TOKEN = 'ghp_super-secret-token-value-do-not-leak'
    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r ->
                    r.statusCode() >= 500 || r.statusCode() == 429
                })
                .build()
    }

    private GithubWorkflowRunPoll pollFor(String baseUrl) {
        def httpClient = new GithubHttpClient(baseUrl, SECRET_TOKEN, fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, 'acme', 'widgets'),
                new GithubWorkflowJobsFetcher(cache, 'acme', 'widgets'))
    }

    def "a persistent 5xx CannotVerify carries no token material in reason or details"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(503).withBody('service unavailable')))
        def poll = pollFor(wireMock.baseUrl())

        when:
        def status = poll.poll('ci.yml', 'abc123') as PollStatus.CannotVerify

        then:
        !status.reason().contains(SECRET_TOKEN)
        !status.details().contains(SECRET_TOKEN)
    }

    def "a network error CannotVerify carries no token material in reason or details"() {
        given:
        def poll = pollFor('http://localhost:1')

        when:
        def status = poll.poll('ci.yml', 'abc123') as PollStatus.CannotVerify

        then:
        !status.reason().contains(SECRET_TOKEN)
        !status.details().contains(SECRET_TOKEN)
    }
}
