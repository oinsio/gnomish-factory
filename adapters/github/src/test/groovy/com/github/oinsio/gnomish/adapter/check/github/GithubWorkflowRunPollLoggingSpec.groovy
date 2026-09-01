package com.github.oinsio.gnomish.adapter.check.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import org.slf4j.MDC
import spock.lang.Specification

/**
 * {@link GithubWorkflowRunPoll}'s per-poll outcome log line: a log line is always emitted, and it
 * names the actual matching run's id rather than a blank placeholder (task 4.2, NFR-O1 of
 * add-external-check-github-actions).
 *
 * <p>And the edge shape a check the factory cannot reach must take (FR4, UX3 of
 * harden-logging-observability): a workflow polled every few seconds for the whole verification
 * window would otherwise write one WARN per tick. What the operator gets instead is the first
 * failure, counted roll-ups on the roll-up interval, DEBUG in between, and one recovery line when
 * the check answers again.
 *
 * <p>Implements NFR-O1 of add-external-check-github-actions; FR4, UX3 of
 * harden-logging-observability.
 */
class GithubWorkflowRunPollLoggingSpec extends Specification {

    private static final String RUNS_URL = '/repos/acme/widgets/actions/workflows/ci.yml/runs?head_sha=abc123&per_page=100'
    private static final Duration ROLL_UP = Duration.ofMinutes(5)

    WireMockServer wireMock
    MovableClock clock = new MovableClock(Instant.parse('2026-08-31T10:00:00Z'))
    RepeatSuppressor suppressor = new RepeatSuppressor(clock, ROLL_UP)
    LogCaptureSupport logs

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        logs?.detach()
        wireMock.stop()
        MDC.clear()
    }

    /**
     * The poll object is rebuilt per tick in production ({@code GithubCheckExternalClient}), so
     * every poll here does the same — which is exactly what makes the suppressor, not the poll,
     * the thing that has to carry the streak.
     */
    private GithubWorkflowRunPoll poll() {
        // A one-attempt retry budget: this spec is about the log line, and the shared client's own
        // retries would otherwise multiply every stubbed failure into a slow WireMock replay.
        def retryConfig = RetryConfig.<HttpResponse<String>> custom()
                .maxAttempts(1)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(1)))
                .build()
        def cache = new GithubConditionalRequestCache(new GithubHttpClient(wireMock.baseUrl(), 'tok', retryConfig))
        new GithubWorkflowRunPoll(
                new GithubWorkflowRunQuery(cache, 'acme', 'widgets'),
                new GithubWorkflowJobsFetcher(cache, 'acme', 'widgets'),
                suppressor)
    }

    private void stubPassing() {
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":42,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed",
                     "conclusion":"success","html_url":"https://github.com/acme/widgets/actions/runs/42"}
                ]}
                ''')))
    }

    private void stubUnverifiable() {
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(401).withBody('{}')))
    }

    def "NFR-O1: a Pass outcome logs a line naming the matching run's actual id and URL"() {
        given:
        stubPassing()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll)

        when:
        poll().poll('ci.yml', 'abc123')

        then: 'exactly one log line is emitted, naming the run id and its actual platform URL'
        logs.list.size() == 1
        logs.list[0].formattedMessage.contains('42')
        logs.list[0].formattedMessage.contains('https://github.com/acme/widgets/actions/runs/42')
    }

    // FR8 of harden-logging-observability: the poll runs on the round's own thread (the verify
    //     chain calls it inline, no hop), so its lines must already carry the attempt's scope —
    //     asserted rather than assumed, because a future thread boundary here would silently
    //     empty the MDC and cost every poll line its `grep taskId=` reachability.
    def "FR8: a poll line lands in the round's task scope"() {
        given:
        stubPassing()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll)
        MDC.put('taskId', 'GH-42')
        MDC.put('stage', 'verify')

        when:
        poll().poll('ci.yml', 'abc123')

        then:
        logs.list[0].MDCPropertyMap['taskId'] == 'GH-42'
        logs.list[0].MDCPropertyMap['stage'] == 'verify'
    }

    def "FR4, UX3: a check that cannot be verified announces once and repeats below the console"() {
        given:
        stubUnverifiable()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll, Level.DEBUG)

        when: 'five polls a minute apart — all inside the roll-up interval'
        5.times {
            poll().poll('ci.yml', 'abc123')
            clock.advance(Duration.ofMinutes(1))
        }

        then: 'the operator console (WARN+) carries the arrival of the fault, once'
        logs.list.findAll { it.level == Level.WARN }.size() == 1
        logs.list.find {
            it.level == Level.WARN
        }.formattedMessage.contains('could not be verified')

        and: 'the four repetitions are diagnosis-only and carry the running count'
        def repeats = logs.list.findAll { it.level == Level.DEBUG }
        repeats.size() == 4
        repeats.last().formattedMessage.contains('5x')
    }

    def "FR4, UX3: a streak that outlives the quiet period rolls up with its count and age"() {
        given:
        stubUnverifiable()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll)

        when: 'the fault arrives, persists quietly, and is still there once the interval elapses'
        poll().poll('ci.yml', 'abc123')
        clock.advance(Duration.ofMinutes(1))
        poll().poll('ci.yml', 'abc123')
        clock.advance(ROLL_UP)
        poll().poll('ci.yml', 'abc123')

        then: 'two console lines for three failed polls: the first occurrence and one roll-up'
        def warnings = logs.list.findAll {
            it.level == Level.WARN
        }.collect {
            it.formattedMessage
        }
        warnings.size() == 2
        warnings[1].contains('3x')
        warnings[1].contains('PT6M')
    }

    def "FR4: the recovery is announced with the outage it ended"() {
        given:
        stubUnverifiable()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll)

        and: 'two failed polls, four minutes apart'
        poll().poll('ci.yml', 'abc123')
        clock.advance(Duration.ofMinutes(4))
        poll().poll('ci.yml', 'abc123')

        when: 'the check answers again'
        wireMock.resetAll()
        stubPassing()
        poll().poll('ci.yml', 'abc123')

        then: 'one INFO names the recovery, its failure count and the outage, before the Pass line'
        def recovery = logs.list.find {
            it.formattedMessage.contains('can be verified again')
        }
        recovery.level == Level.INFO
        recovery.formattedMessage.contains('2 failed poll(s)')
        recovery.formattedMessage.contains('PT4M')

        and: 'the poll that recovered still logs its own verdict'
        logs.list.any { it.formattedMessage.contains('passed') }
    }

    // FR4: the outage ends on whatever verdict the check finally answers with — not only a Pass.
    // Every non-CannotVerify arm closes the streak, or a check that recovers into a red build (or
    // into a still-running one) would go on looking down forever.
    def "FR4: the recovery is announced whichever verdict ends the outage (#label)"() {
        given:
        stubUnverifiable()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll, Level.DEBUG)

        and: 'one failed poll'
        poll().poll('ci.yml', 'abc123')
        clock.advance(Duration.ofMinutes(4))

        when: 'the check answers again, with a verdict that is not a Pass'
        wireMock.resetAll()
        "stub${stub}"()
        poll().poll('ci.yml', 'abc123')

        then:
        def recovery = logs.list.find {
            it.formattedMessage.contains('can be verified again')
        }
        recovery != null
        recovery.level == Level.INFO
        recovery.formattedMessage.contains('1 failed poll(s)')

        and: 'the poll that recovered still logs its own verdict'
        logs.list.any { it.formattedMessage.contains(verdictWord as String) }

        where:
        label | stub | verdictWord
        'failed' | 'Failing' | 'failed'
        'running' | 'Running' | 'still running'
    }

    private void stubFailing() {
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":42,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"completed","conclusion":"failure"}
                ]}
                ''')))
        wireMock.stubFor(get(urlMatching('/repos/acme/widgets/actions/runs/42/jobs.*'))
                .willReturn(aResponse().withStatus(200).withBody('{"jobs":[]}')))
    }

    private void stubRunning() {
        wireMock.stubFor(get(urlEqualTo(RUNS_URL)).willReturn(aResponse().withStatus(200).withBody('''
                {"workflow_runs":[
                    {"id":42,"head_sha":"abc123","path":"ci.yml","run_attempt":1,"status":"in_progress"}
                ]}
                ''')))
    }

    def "FR4: a healthy check that never failed announces no recovery"() {
        given:
        stubPassing()
        logs = LogCaptureSupport.attach(GithubWorkflowRunPoll)

        when:
        3.times { poll().poll('ci.yml', 'abc123') }

        then: 'three verdict lines and nothing else — a streak that never started ends nothing'
        logs.list.size() == 3
        logs.list.every { it.formattedMessage.contains('passed') }
    }
}
