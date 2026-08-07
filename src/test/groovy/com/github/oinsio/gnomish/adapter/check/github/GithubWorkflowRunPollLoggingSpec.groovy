package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.tomakehurst.wiremock.WireMockServer
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * {@link GithubWorkflowRunPoll}'s per-poll outcome log line (task 4.2, NFR-O1 of
 * add-external-check-github-actions): a log line is always emitted, and it names the actual
 * matching run's id rather than a blank placeholder.
 *
 * <p>Implements NFR-O1 of add-external-check-github-actions.
 */
class GithubWorkflowRunPollLoggingSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(GithubWorkflowRunPoll)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    def "a Pass outcome logs a line naming the matching run's actual id"() {
        given:
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":42,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"success"}
                ]}
                ''')))
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok')
        def cache = new GithubConditionalRequestCache(httpClient)
        def poll = new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, 'acme', 'widgets'),
                new GithubWorkflowJobsFetcher(cache, 'acme', 'widgets'))

        when:
        def events = capture { poll.poll('ci.yml', 'abc123') }

        then: 'exactly one log line is emitted, naming the run id rather than a blank placeholder'
        events.size() == 1
        events[0].formattedMessage.contains('42')
    }
}
