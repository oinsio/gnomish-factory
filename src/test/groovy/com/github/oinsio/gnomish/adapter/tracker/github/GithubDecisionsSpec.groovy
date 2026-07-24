package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubDecisions (FR12, UX3 of add-tracker-port; design D9, D13):
 * {@code collectDecisions} finds the latest ACK-kind structural marker and
 * returns every non-marker comment after it, in posting order;
 * {@code acknowledgeDecision} posts an ACK-kind structural comment naming
 * the decision text, anchoring future collection past it.
 *
 * Implements FR12 of add-tracker-port.
 */
class GithubDecisionsSpec extends Specification {

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

    private GithubDecisions newDecisions(String instanceId = 'gnomish-factory-x7k2q1') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubDecisions(httpClient, instanceId)
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    private static void stubComments(WireMockServer wireMock, int issueNumber, String commentsJsonArray) {
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody(commentsJsonArray)))
    }

    def "no ack yet: every non-marker comment on the issue counts as a human reply"() {
        given:
        stubComments(wireMock, 30, '''
                [
                  {"id":1,"created_at":"2026-07-20T09:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"v\\":1} -->\\n🤖 claimed"},
                  {"id":2,"created_at":"2026-07-20T09:05:00Z","body":"Please use approach B instead."},
                  {"id":3,"created_at":"2026-07-20T09:06:00Z","body":"<!-- gnomish {\\"kind\\":\\"report\\",\\"instance\\":\\"gnomish-factory-a\\",\\"at\\":\\"2026-07-20T09:06:00Z\\",\\"v\\":1} -->\\n🤖 report"},
                  {"id":4,"created_at":"2026-07-20T09:07:00Z","body":"Actually go with C."}
                ]
                ''')
        def decisions = newDecisions()

        when:
        def replies = decisions.collectDecisions(refFor(30))

        then: 'both plain-text replies count, structural markers (claim/report) are skipped, order preserved'
        replies == [
            new HumanReply('Please use approach B instead.', Instant.parse('2026-07-20T09:05:00Z')),
            new HumanReply('Actually go with C.', Instant.parse('2026-07-20T09:07:00Z')),
        ]
    }

    def "ack consumes decisions: only replies after the latest ack are returned"() {
        given:
        stubComments(wireMock, 31, '''
                [
                  {"id":1,"created_at":"2026-07-20T09:00:00Z","body":"Old reply, already acted on."},
                  {"id":2,"created_at":"2026-07-20T09:01:00Z","body":"<!-- gnomish {\\"kind\\":\\"ack\\",\\"instance\\":\\"gnomish-factory-a\\",\\"at\\":\\"2026-07-20T09:01:00Z\\",\\"v\\":1} -->\\n🤖 acting on decision: Old reply, already acted on."},
                  {"id":3,"created_at":"2026-07-20T09:02:00Z","body":"New reply after the ack."}
                ]
                ''')
        def decisions = newDecisions()

        when:
        def replies = decisions.collectDecisions(refFor(31))

        then:
        replies == [
            new HumanReply('New reply after the ack.', Instant.parse('2026-07-20T09:02:00Z'))
        ]
    }

    def "stale replies never resurface: nothing before the latest ack reappears, even past a later marker"() {
        given: 'an old decision was acked, then a new escalation posted a report marker with no new human reply'
        stubComments(wireMock, 32, '''
                [
                  {"id":1,"created_at":"2026-07-20T09:00:00Z","body":"Old decision."},
                  {"id":2,"created_at":"2026-07-20T09:01:00Z","body":"<!-- gnomish {\\"kind\\":\\"ack\\",\\"instance\\":\\"gnomish-factory-a\\",\\"at\\":\\"2026-07-20T09:01:00Z\\",\\"v\\":1} -->\\n🤖 acting on decision: Old decision."},
                  {"id":3,"created_at":"2026-07-20T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"report\\",\\"instance\\":\\"gnomish-factory-a\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"v\\":1} -->\\n🤖 new escalation report"}
                ]
                ''')
        def decisions = newDecisions()

        when:
        def replies = decisions.collectDecisions(refFor(32))

        then:
        replies.isEmpty()
    }

    def "collectDecisions is empty right after acknowledgeDecision, then picks up only the next human reply"() {
        given:
        stubComments(wireMock, 33, '''
                [
                  {"id":1,"created_at":"2026-07-20T09:00:00Z","body":"Use approach B."},
                  {"id":2,"created_at":"2026-07-20T09:05:00Z","body":"<!-- gnomish {\\"kind\\":\\"ack\\",\\"instance\\":\\"gnomish-factory-x7k2q1\\",\\"at\\":\\"2026-07-20T09:05:00Z\\",\\"v\\":1} -->\\n🤖 gnomish: acting on decision: Use approach B."}
                ]
                ''')
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/33/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":2,"body":"whatever"}')))
        def decisions = newDecisions()

        when: 'the factory acknowledges the decision, then re-collects'
        decisions.acknowledgeDecision(refFor(33), 'Use approach B.')
        def replies = decisions.collectDecisions(refFor(33))

        then: 'nothing is left to collect: the ack anchors past the consumed reply'
        replies.isEmpty()
    }

    def "acknowledgeDecision posts a correctly-shaped ACK structural comment"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/34/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":99,"body":"whatever"}')))
        def decisions = newDecisions('gnomish-factory-x7k2q1')

        when:
        decisions.acknowledgeDecision(refFor(34), 'Use approach B.')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/34/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"ack"')))
                .withRequestBody(WireMock.matchingJsonPath(
                '$.body', WireMock.containing('"instance":"gnomish-factory-x7k2q1"')))
                .withRequestBody(WireMock.matchingJsonPath(
                '$.body', WireMock.containing('acting on decision: Use approach B.'))))
    }
}
