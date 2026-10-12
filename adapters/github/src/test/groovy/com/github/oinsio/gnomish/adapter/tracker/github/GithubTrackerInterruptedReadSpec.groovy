package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.github.oinsio.gnomish.adapter.github.GithubCallInterruptedException
import com.github.oinsio.gnomish.adapter.github.InterruptMidRequest
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import spock.lang.Specification

/**
 * An interrupted tracker read is a cancellation, not an outage (FR11, NFR-R3 of
 * fix-operator-blockers): the open listing is interrupted while GitHub holds its answer back, and
 * the caller reads the stop from its own thread rather than from a retried failure. The tracker is
 * wired with a retry policy that retries every exception, so a cancellation mistaken for a
 * transport failure would show as a second request.
 */
class GithubTrackerInterruptedReadSpec extends Specification implements GithubTrackerWireMockFixture {

    private static final String OPEN_LISTING = '/repos/acme/widgets/issues\\?state=open.*'

    // FR11, NFR-R3 of fix-operator-blockers: the listOpen read is interrupted mid-request — it
    //     fails, the interrupt is still set when the failure reaches the caller, and no second
    //     request is sent.
    // Scenario: tracker/tracker-port / Interrupted read leaves the interrupt set
    def "an interrupted open listing fails with the interrupt kept and sends no second request"() {
        given: 'the open listing answers only long after the interrupt'
        wireMock.stubFor(get(urlMatching(OPEN_LISTING))
                .willReturn(aResponse().withStatus(200).withBody('[]').withFixedDelay(30_000)))
        def tracker = newTracker()

        when:
        def outcome = InterruptMidRequest.run(wireMock, getRequestedFor(urlMatching(OPEN_LISTING))) {
            tracker.listOpen()
        }

        then: 'the call failed as the cancellation it is, never as a retryable outage'
        outcome.failure instanceof GithubCallInterruptedException
        !(outcome.failure instanceof TrackerUnavailableException)

        and: 'the caller reads the stop from its own thread'
        outcome.interruptSet

        and: 'no second request was sent'
        wireMock.countRequestsMatching(getRequestedFor(urlMatching(OPEN_LISTING)).build()).count == 1
    }
}
