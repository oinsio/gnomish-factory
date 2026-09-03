package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * GithubForeignRepoCheck (FR9, design D8): a canonical id whose owner/repo
 * differs from the configured binding is tolerated only when GitHub's own
 * {@code full_name} for that repo resolves to the configured repo (a
 * pre-rename id) — otherwise it is refused, naming both repos.
 *
 * Implements FR9, FR16 of add-tracker-port.
 */
class GithubForeignRepoCheckSpec extends Specification {

    WireMockServer wireMock
    GithubForeignRepoCheck check

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok')
        check = new GithubForeignRepoCheck(httpClient)
    }

    def cleanup() {
        wireMock.stop()
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec. */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(GithubForeignRepoCheck)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    def "id already names the configured repo: no HTTP call is made"() {
        given:
        def id = new GithubTaskId('', 'acme', 'widgets', 42)

        when:
        check.verify(id, 'acme', 'widgets')

        then:
        wireMock.findAll(getRequestedFor(urlEqualTo('/repos/acme/widgets'))).isEmpty()
    }

    // FR12 of harden-logging-observability: a rename redirect that resolved is a recovered
    //     transient — the verification succeeded — so it is INFO, off the operator console.
    def "foreign repo whose full_name resolves to the configured repo: proceeds with an INFO"() {
        given: 'the id names a pre-rename repo that GitHub now reports under the configured owner/repo'
        wireMock.stubFor(get(urlEqualTo('/repos/old-org/widgets'))
                .willReturn(aResponse().withStatus(200).withBody('{"full_name":"acme/widgets"}')))
        def id = new GithubTaskId('', 'old-org', 'widgets', 42)

        when:
        def events = capture {
            check.verify(id, 'acme', 'widgets')
        }

        then: 'no exception, and one INFO names both the id repo and the configured target'
        events.any {
            it.level == Level.INFO && it.formattedMessage.contains('old-org/widgets') && it.formattedMessage.contains('acme/widgets')
        }

        and: 'nothing reaches the operator console for a redirect that resolved'
        events.every { it.level != Level.WARN }
    }

    def "foreign repo whose full_name resolves elsewhere: refused, naming both repos"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/other-org/other-repo'))
                .willReturn(aResponse().withStatus(200).withBody('{"full_name":"other-org/renamed-repo"}')))
        def id = new GithubTaskId('', 'other-org', 'other-repo', 7)

        when:
        check.verify(id, 'acme', 'widgets')

        then:
        def e = thrown(GithubForeignRepoException)
        e.message.contains('other-org/other-repo')
        e.message.contains('acme/widgets')
    }

    def "the id's repo does not exist (404): treated as a foreign-repo refusal"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/ghost-org/ghost-repo'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def id = new GithubTaskId('', 'ghost-org', 'ghost-repo', 1)

        when:
        check.verify(id, 'acme', 'widgets')

        then:
        def e = thrown(GithubForeignRepoException)
        e.message.contains('ghost-org/ghost-repo')
        e.message.contains('acme/widgets')
    }
}
