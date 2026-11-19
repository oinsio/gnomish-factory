package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.SHA
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.run
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.status

import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR4, NFR-R2, NFR-C1 of migrate-verify-to-executor-contract, design D3, D13: the {@code scm-status}
 * built-in reads the named verdicts on the attempt SHA — commit statuses, and GitHub's check runs
 * where the platform has them — latest per context, and answers {@code pending} while an expected
 * context is absent or running, {@code completed} with one result per failed context once all have
 * a verdict. A read it cannot answer is
 * {@code ScmStatusUnansweredSpec}'s.
 */
class ScmStatusExecutorSpec extends Specification {

    WireMockServer scm = new WireMockServer(0)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    def "the describe declares a polling verdict built-in with its token, its endpoint's host only and a known-zero usage"() {
        when:
        def describe = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).describe()

        then:
        describe.roles() == [Role.VERDICT]
        describe.capabilities() == [Capability.POLL]
        describe.credentials() == ['GNOMISH_SCM_STATUS_TOKEN']
        describe.egress() == [
            URI.create(scm.baseUrl()).host
        ]
        describe.settingsSchema().toString() == '{"type":"object","additionalProperties":false}'
        describe.reportsUsage()
        !describe.checkIdIsPinnedPath()
    }

    // Scenario: verification/verdict-executors / Absent context stays pending
    def "with one of two expected contexts reported green, the run is pending on the attempt SHA naming the absent one"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', 'success')])
        ScmStatusRig.noChecksApi(scm)
        def executor = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'lint'])

        when:
        def started = executor.start(ScmStatusRig.verdict())

        then:
        started.status() == Status.PENDING
        started.pendingKey() == new PendingKey(SHA)
        started.reason().forLog() == "waiting on 'lint' (absent)"
        started.usage().provenance() == UsageProvenance.REPORTED

        when: 'the second context reported by the next poll'
        ScmStatusRig.statuses(scm, [
            status(1, 'build', 'success'),
            status(2, 'lint', 'success')
        ])
        def polled = executor.poll(ScmStatusRig.verdict(), started.pendingKey())

        then:
        polled.status() == Status.COMPLETED
        ScmStatusRig.results(polled) == []
    }

    def "a running context keeps the run pending, by status and by check run alike"() {
        given:
        ScmStatusRig.statuses(scm, [status(1, 'build', 'pending')])
        ScmStatusRig.checkRuns(scm, [
            run(7, 'test', 'in_progress', null)
        ])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'test']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.reason().forLog() == "waiting on 'build' (running), 'test' (running)"
    }

    // Scenario: verification/verdict-executors / Same-named duplicate uses the latest
    def "a context that reported failure then success on the same SHA counts as passed, in either listing"() {
        given:
        ScmStatusRig.statuses(scm, [
            status(2, 'build', 'success'),
            status(1, 'build', 'failure', 'red')
        ])
        ScmStatusRig.checkRuns(scm, [
            run(5, 'test', 'completed', 'failure'),
            run(9, 'test', 'completed', 'success')
        ])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'test']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.COMPLETED
        ScmStatusRig.results(result) == []
    }

    def "every failed expected context is an error result naming it, a warned one a warning, and an unexpected one is not read"() {
        given:
        ScmStatusRig.statuses(scm, [
            status(1, 'build', 'failure', 'compile broke'),
            status(2, 'lint', 'warning'),
            status(3, 'docs', 'error', 'not expected')
        ])
        ScmStatusRig.checkRuns(scm, [
            run(4, 'test', 'completed', 'timed_out')
        ])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'lint', 'test']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.COMPLETED
        def results = ScmStatusRig.results(result)
        results*.level() == [
            SarifLevel.ERROR,
            SarifLevel.WARNING,
            SarifLevel.ERROR
        ]
        results*.uri()*.forLog() == ['build', 'lint', 'test']
        results*.message()*.forLog() == [
            "context 'build' reported failure: compile broke",
            "context 'lint' reported warning",
            "context 'test' reported timed_out"
        ]
    }

    def "a context reported by both listings takes the state further from a pass"() {
        given:
        ScmStatusRig.statuses(scm, [
            status(50, 'build', statusState)
        ])
        ScmStatusRig.checkRuns(scm, [
            run(1, 'build', runStatus, runConclusion)
        ])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == expected

        where:
        statusState | runStatus | runConclusion || expected
        'success' | 'completed' | 'failure' || Status.COMPLETED
        'failure' | 'completed' | 'success' || Status.COMPLETED
        'success' | 'queued' | null || Status.PENDING
        'pending' | 'completed' | 'success' || Status.PENDING
    }

    def "a red context reported by both listings fails the check whichever listing said it"() {
        given:
        ScmStatusRig.statuses(scm, [
            status(50, 'build', statusState)
        ])
        ScmStatusRig.checkRuns(scm, [
            run(1, 'build', 'completed', runConclusion)
        ])

        when:
        def results = ScmStatusRig.results(ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict()))

        then:
        results*.message()*.forLog() == [
            "context 'build' reported ${said}"
        ]

        where:
        statusState | runConclusion || said
        'success' | 'failure' || 'failure'
        'failure' | 'success' || 'failure'
        'warning' | 'success' || 'warning'
        'success' | 'warning' || 'warning'
    }
}
