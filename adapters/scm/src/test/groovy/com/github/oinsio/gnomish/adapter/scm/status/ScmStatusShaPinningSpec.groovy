package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.PREVIOUS
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.SHA
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.run
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.status

import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR9 of migrate-verify-to-executor-contract, design D9 (the identity spec of the {@code scm-status}
 * row): the contexts the built-in reads are exactly those the SCM holds for the attempt SHA. Both
 * listings are asked by the SHA in their path, and a report the platform answers on another commit
 * — a combined status naming the previous attempt, a check run whose head is the previous attempt —
 * is dropped as if absent, so it keeps the expected context waiting and never passes it.
 */
class ScmStatusShaPinningSpec extends Specification {

    WireMockServer scm = new WireMockServer(0)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    def "the verdicts read are those the SCM holds for the attempt SHA, asked by that SHA, not the previous attempt's"() {
        given: 'the SCM holds a green build on the previous attempt and a red one on this attempt'
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')], PREVIOUS)
        ScmStatusRig.checkRuns(scm, [
            run(2, 'test', 'completed', 'success', PREVIOUS)
        ], PREVIOUS)
        ScmStatusRig.statuses(scm, [
            status(3, 'build', 'failure', 'red')
        ])
        ScmStatusRig.checkRuns(scm, [
            run(4, 'test', 'completed', 'failure')
        ])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'test']).start(ScmStatusRig.verdict())

        then:
        ScmStatusRig.results(result)*.message()*.forLog() == [
            "context 'build' reported failure: red",
            "context 'test' reported failure"
        ]
        scm.allServeEvents*.request*.url.toSet() == [
            ScmStatusRig.statusPath(),
            ScmStatusRig.checkRunsPath()
        ] as Set
    }

    // Scenario: verification/verdict-executors / Status on another SHA is ignored
    def "the only report of an expected context on the previous attempt's SHA leaves the run pending: #listing"() {
        given:
        ScmStatusRig.statuses(scm, statuses, SHA, reportedSha)
        ScmStatusRig.checkRuns(scm, runs)

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.pendingKey() == new PendingKey(SHA)
        result.reason().forLog() == "waiting on 'build' (absent)"

        where:
        listing | statuses | reportedSha | runs
        'a combined status naming another commit' | [status(1, 'build', 'success')] | PREVIOUS | []
        'a check run headed on another commit' | [] | SHA | [
            run(1, 'build', 'completed', 'success', PREVIOUS)
        ]
    }

    def "a poll of the pending key reads the key's SHA again, and the platform's object name compares without case"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')], SHA, SHA.toUpperCase())
        ScmStatusRig.checkRuns(scm, [])

        when:
        def polled = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build'], PREVIOUS)
        .poll(ScmStatusRig.verdict(), new PendingKey(SHA))

        then:
        polled.status() == Status.COMPLETED
        ScmStatusRig.results(polled) == []
    }
}
