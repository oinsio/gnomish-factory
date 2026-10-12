package com.github.oinsio.gnomish.adapter.tracker.github

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
import spock.lang.Specification

/**
 * NFR-P1 of add-factory-serve; FR8 of add-tracker-port: the feed's poll of an unchanged ready
 * queue is a conditional request, and GitHub's answer to it is {@code 304 Not Modified} — the
 * response GitHub's REST API documents as not counted against the primary rate limit. The queue
 * the feed then reads is the one the first poll returned, so nothing about the answer is lost.
 */
class GithubFeedUnchangedQueueSpec extends Specification {

    private static final String FEED =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'

    private static final String ONE_READY = '[{"number":7,"title":"Add widgets","labels":[{"name":"gnomish:ready"}]}]'

    WireMockServer wireMock = new WireMockServer(0)

    def setup() {
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private GithubFeedQuery feedQuery() {
        def retry = RetryConfig.custom().maxAttempts(1).intervalFunction(IntervalFunction.of(10)).build()
        new GithubFeedQuery(new GithubConditionalRequestCache(new GithubHttpClient(wireMock.baseUrl(), 'tok', retry)),
                'acme', 'widgets', 'gnomish:ready')
    }

    // Scenario: serve/factory-serve / An unchanged queue costs no rate-limit budget
    def "NFR-P1: the second poll of an unchanged queue is conditional, answered 304, and reads the same queue"() {
        given: 'the queue answers with an ETag, and with 304 to a request carrying it'
        wireMock.stubFor(get(urlEqualTo(FEED)).withHeader('If-None-Match', absent())
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"q1"').withBody(ONE_READY)))
        wireMock.stubFor(get(urlEqualTo(FEED)).withHeader('If-None-Match', equalTo('"q1"'))
                .willReturn(aResponse().withStatus(304)))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def feed = feedQuery()
        def first = feed.listReady(10)

        when: 'the feed polls again with nothing changed'
        def second = feed.listReady(10)

        then: 'the poll carried the validator, and the answer was 304 rather than a fresh listing'
        wireMock.verify(1, getRequestedFor(urlEqualTo(FEED)).withHeader('If-None-Match', equalTo('"q1"')))
        wireMock.findAll(getRequestedFor(urlEqualTo(FEED))).size() == 2

        and: 'the queue read from the 304 is the one the first poll returned'
        second*.ref() == first*.ref()
        second*.ref()*.id() == [
            'github:localhost/acme/widgets#7'
        ]
    }
}
