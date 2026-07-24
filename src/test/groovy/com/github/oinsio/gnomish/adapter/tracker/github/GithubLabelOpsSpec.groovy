package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.put
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * GithubLabelOps (FR5 of add-tracker-port, design risk "human edits labels
 * concurrently"): verifies the point add/remove label primitives and the
 * exclusive-transition composite issue only the documented point calls —
 * never a whole-set {@code PUT .../labels} replacement — so a concurrent
 * human label edit is never lost.
 *
 * Implements FR5 of add-tracker-port.
 */
class GithubLabelOpsSpec extends Specification {

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
                .retryOnException({ it instanceof GithubHttpUncheckedIOException })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubLabelOps newOps() {
        new GithubLabelOps(new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig()))
    }

    def "addLabel posts a point-add to the issue labels endpoint naming only the new label"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def ops = newOps()

        when:
        ops.addLabel('acme', 'widgets', 42, 'gnomish:working')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:working"]}')))
    }

    def "removeLabel sends a DELETE naming only the removed label"() {
        given:
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def ops = newOps()

        when:
        ops.removeLabel('acme', 'widgets', 42, 'gnomish:ready')

        then:
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready')))
    }

    def "removeLabel treats a 404 (label already absent) as a no-op success"() {
        given:
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Label does not exist"}')))
        def ops = newOps()

        when:
        ops.removeLabel('acme', 'widgets', 42, 'gnomish:ready')

        then:
        noExceptionThrown()
    }

    def "removeLabel surfaces a non-404 error response"() {
        given:
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready'))
                .willReturn(aResponse().withStatus(403).withBody('{"message":"Forbidden"}')))
        def ops = newOps()

        when:
        ops.removeLabel('acme', 'widgets', 42, 'gnomish:ready')

        then:
        thrown(GithubLabelOpsException)
    }

    def "addLabel surfaces a non-2xx error response"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .willReturn(aResponse().withStatus(410).withBody('{"message":"Gone"}')))
        def ops = newOps()

        when:
        ops.addLabel('acme', 'widgets', 42, 'gnomish:working')

        then:
        thrown(GithubLabelOpsException)
    }

    def "transition performs exactly one add and one remove, never a whole-set PUT, leaving unrelated labels untouched"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(put(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def ops = newOps()

        when:
        ops.transition('acme', 'widgets', 42, 'gnomish:ready', 'gnomish:working')

        then:
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:working"]}')))
        wireMock.verify(1, deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready')))
        wireMock.verify(0, WireMock.putRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels')))
    }

    def "transition adds the new-state label before removing the old-state label"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def ops = newOps()

        when:
        ops.transition('acme', 'widgets', 42, 'gnomish:ready', 'gnomish:working')

        then:
        // the add happens before the remove: with fast fail-fast retries this
        // shows up as strict ordering of the two verified requests below.
        def addEvent = wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels')))[0]
        def removeEvent = wireMock.findAll(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aready')))[0]
        addEvent.loggedDate <= removeEvent.loggedDate
    }
}
