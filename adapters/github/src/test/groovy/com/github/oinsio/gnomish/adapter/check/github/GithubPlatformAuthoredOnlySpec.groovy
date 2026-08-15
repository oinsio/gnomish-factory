package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * Proves design D1 / FR3 / M2 of add-external-check-github-actions: the
 * verdict comes exclusively from the workflow-run conclusion, never from a
 * commit-status or check-run endpoint. A repo-scoped token can forge a green
 * commit status; it cannot forge a workflow-run conclusion. This spec stubs
 * both a real run (concluding {@code failure}) and a forged, token-created
 * green commit status for the same attempt commit, drives the real
 * production path ({@link GithubWorkflowRunQuery} then {@link
 * GithubWorkflowRunVerdict}), and asserts the forged status is both ignored
 * (verdict is Fail) and never even requested.
 *
 * <p>The structural half of the proof — that no class in {@code
 * adapter/check/github} builds a commit-status or check-run URL — is
 * grep-verifiable: {@code grep -rn "/status\"\|/check-runs/\|/statuses\""
 * src/main/java/com/github/oinsio/gnomish/adapter/check/github/} returns no
 * URL-path match (the only unrelated hit is a {@code "check-runs:"}
 * conditional-request cache-key label in {@link GithubWorkflowRunQuery},
 * never a request path). {@link GithubWorkflowRunQuery} builds and requests
 * only the workflow-runs listing endpoint (D1), and this spec's zero-hit
 * assertion on the stubbed status endpoint below is the runtime witness of
 * that fact.
 *
 * <p>Implements FR3, M2 of add-external-check-github-actions.
 */
class GithubPlatformAuthoredOnlySpec extends Specification {

    private static final String RUNS_URL =
    '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    private static final String FORGED_STATUS_URL_PATTERN =
    '/repos/acme/widgets/commits/.*/status.*'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubWorkflowRunQuery newQuery() {
        def retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', retryConfig)
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubWorkflowRunQuery(cache, 'acme', 'widgets')
    }

    def "a forged token-created success status alongside a red run conclusion yields Fail, and the status endpoint is never queried"() {
        given: 'the real workflow run concludes failure'
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"failure"}
                ]}
                ''')))

        and: 'a forged commit status, creatable with any repo-scoped token, claims success'
        wireMock.stubFor(get(urlMatching(FORGED_STATUS_URL_PATTERN)).willReturn(aResponse().withStatus(200).withBody('''
                {"state":"success","statuses":[{"state":"success","context":"forged-by-gnome","creator":{"login":"gnome-token"}}]}
                ''')))

        when:
        def matchingRun = newQuery().latestMatchingRun('ci.yml', 'abc123')
        def verdict = GithubWorkflowRunVerdict.fromMatchingRun(matchingRun)

        then: 'the red run conclusion wins, fail-closed'
        verdict instanceof PollStatus.Fail

        and: 'the forged status endpoint was never hit'
        wireMock.verify(0, getRequestedFor(urlMatching(FORGED_STATUS_URL_PATTERN)))

        and: 'only the workflow-runs listing endpoint was queried'
        wireMock.verify(getRequestedFor(urlEqualTo(RUNS_URL)))
    }
}
