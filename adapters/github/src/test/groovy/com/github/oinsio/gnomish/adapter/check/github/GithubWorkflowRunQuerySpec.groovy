package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.absent
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * GithubWorkflowRunQuery (FR1, FR5 of add-external-check-github-actions):
 * verifies the run query is scoped to the attempt commit and the declared
 * checkId workflow, that unrelated workflows/commits never influence the
 * match, and that a re-run's latest attempt wins.
 *
 * Implements FR1, FR5 of add-external-check-github-actions.
 */
class GithubWorkflowRunQuerySpec extends Specification {

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
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubWorkflowRunQuery newQuery(String owner = 'acme', String repo = 'widgets') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubWorkflowRunQuery(cache, owner, repo)
    }

    def "scopes the runs query by the checkId workflow file name, not its full path, and matches GitHub's full-path run.path"() {
        given: 'a checkId given as a full workflow path, and GitHub echoing that full path in run.path'
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {
                          "workflow_runs": [
                            {"id":1,"head_sha":"abc123","path":".github/workflows/ci.yml","run_attempt":1,"status":"completed","conclusion":"success","html_url":"https://example/runs/1"}
                          ]
                        }
                        ''')))
        def query = newQuery()

        when:
        def result = query.latestMatchingRun('.github/workflows/ci.yml', 'abc123')

        then: 'the endpoint is scoped by the bare file name (the full path 404s on Gitea), and the run still matches'
        result.isPresent()
        result.get().id() == 1L
        result.get().conclusion() == 'success'
        result.get().htmlUrl() == 'https://example/runs/1'
        wireMock.verify(getRequestedFor(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100')))
    }

    def "matches a Gitea run whose path carries a @refs/heads ref suffix (live-E2E shape, task 7.1)"() {
        given: 'Gitea reports run.path as <fileName>@refs/heads/<branch> rather than the workflow file path'
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/smoke.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {
                          "workflow_runs": [
                            {"id":7,"head_sha":"abc123","path":"smoke.yml@refs/heads/main","run_attempt":1,"status":"completed","conclusion":"success"}
                          ]
                        }
                        ''')))
        def query = newQuery()

        when: 'the check is declared with the full workflow path, as the stage law carries it'
        def result = query.latestMatchingRun('.gitea/workflows/smoke.yml', 'abc123')

        then: 'reducing both sides to the file name matches the concluded run'
        result.isPresent()
        result.get().id() == 7L
        result.get().conclusion() == 'success'
    }

    def "excludes a run whose head_sha does not match the attempt commit"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {
                          "workflow_runs": [
                            {"id":1,"head_sha":"other-sha","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                          ]
                        }
                        ''')))
        def query = newQuery()

        when:
        def result = query.latestMatchingRun('ci.yml', 'abc123')

        then:
        result.isEmpty()
    }

    def "excludes a run whose path is a different workflow than the queried workflow"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {
                          "workflow_runs": [
                            {"id":1,"head_sha":"abc123","path":"lint.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                          ]
                        }
                        ''')))
        def query = newQuery()

        when:
        def result = query.latestMatchingRun('ci.yml', 'abc123')

        then:
        result.isEmpty()
    }

    def "the newest run_attempt supersedes earlier attempts for the same head_sha"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {
                          "workflow_runs": [
                            {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"failure"},
                            {"id":2,"head_sha":"abc123","path":"ci.yml","run_attempt":2,"status":"completed","conclusion":"success"}
                          ]
                        }
                        ''')))
        def query = newQuery()

        when:
        def result = query.latestMatchingRun('ci.yml', 'abc123')

        then:
        result.isPresent()
        result.get().id() == 2L
        result.get().runAttempt() == 2
        result.get().conclusion() == 'success'
    }

    def "returns empty when no run has been listed yet for the attempt commit"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('{"workflow_runs":[]}')))
        def query = newQuery()

        when:
        def result = query.latestMatchingRun('ci.yml', 'abc123')

        then:
        result.isEmpty()
    }

    def "reuses the conditional-request cache across repeated polls of the same attempt commit"() {
        given:
        wireMock.stubFor(get(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('{"workflow_runs":[]}')))
        def query = newQuery()
        query.latestMatchingRun('ci.yml', 'abc123')

        when:
        query.latestMatchingRun('ci.yml', 'abc123')

        then:
        wireMock.verify(getRequestedFor(urlEqualTo(
                        '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .withHeader('If-None-Match', equalTo('"v1"')))
    }

    def "a 304 Not Modified response reuses the previously cached runs body instead of an empty one"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .withHeader('If-None-Match', absent())
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"v1"').withBody('''
                        {"workflow_runs":[
                            {"id":1,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                        ]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'))
                .withHeader('If-None-Match', equalTo('"v1"'))
                .willReturn(aResponse().withStatus(304)))
        def query = newQuery()
        query.latestMatchingRun('ci.yml', 'abc123')

        when: 'the platform reports the runs listing is unchanged'
        def result = query.latestMatchingRun('ci.yml', 'abc123')

        then: 'the previously cached body is reused, still yielding the matching run'
        result.isPresent()
        result.get().id() == 1L
    }
}
