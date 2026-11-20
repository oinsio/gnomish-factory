package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.SHA
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.run
import static com.github.oinsio.gnomish.adapter.scm.status.ScmStatusRig.status
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.executorcontract.PendingState
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.tomakehurst.wiremock.WireMockServer
import spock.lang.Specification

/**
 * FR4, NFR-R2 of migrate-verify-to-executor-contract, design D3: the {@code scm-status} built-in
 * reads every page of both listings — a commit with more than a hundred reports holds an expected
 * context on a later page, and reading the first page only would keep it absent until the check's
 * timeout, a false red. The pages are bounded by {@link ScmStatusReader#MAX_PAGES}; reports the
 * bound or a short listing left unread are named in the pending reason, never silently absent.
 */
class ScmStatusPaginationSpec extends Specification {

    static final String STATUS = "/repos/acme/widgets/commits/${SHA}/status?per_page=100"
    static final String RUNS = "/repos/acme/widgets/commits/${SHA}/check-runs?per_page=100"

    WireMockServer scm = new WireMockServer(0)

    def setup() {
        scm.start()
    }

    def cleanup() {
        scm.stop()
    }

    def "an expected check run on the second page is read, and the listing stops at its total"() {
        given:
        ScmStatusRig.statuses(scm, [])
        serve(RUNS, [total_count: 101, check_runs: others(100)])
        serve(RUNS + '&page=2', [total_count: 101, check_runs: [
                run(500, 'build', 'completed', 'failure')
            ]])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.COMPLETED
        ScmStatusRig.results(result)*.message()*.forLog() == [
            "context 'build' reported failure"
        ]
        scm.allServeEvents.size() == 3
    }

    def "an expected status on the second page of the combined status is read"() {
        given:
        serve(STATUS, combined(101, (1..100).collect {
            status(it, "other-${it}", 'success')
        }))
        serve(STATUS + '&page=2', combined(101, [
            status(500, 'build', 'success')
        ]))
        ScmStatusRig.noChecksApi(scm)

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.COMPLETED
        ScmStatusRig.results(result) == []
    }

    def "a listing longer than the page bound is read up to the bound, and the absent context names what was not read"() {
        given:
        ScmStatusRig.statuses(scm, [])
        int total = (ScmStatusReader.MAX_PAGES + 1) * 100
        serve(RUNS, [total_count: total, check_runs: others(100)])
        (2..ScmStatusReader.MAX_PAGES + 1).each {
            serve(RUNS + "&page=${it}", [total_count: total, check_runs: others(100)])
        }

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.status() == Status.PENDING
        result.pendingState() == PendingState.UNREPORTED
        result.reason().forLog() == "waiting on 'build' (absent; 100 of the SCM's reports on the SHA were not read)"
        scm.allServeEvents.size() == 1 + ScmStatusReader.MAX_PAGES
    }

    def "an empty page ends the listing before its total, and the shortfall is named"() {
        given:
        ScmStatusRig.statuses(scm, [])
        serve(RUNS, [total_count: 500, check_runs: others(100)])
        serve(RUNS + '&page=2', [total_count: 500, check_runs: []])

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build']).start(ScmStatusRig.verdict())

        then:
        result.reason().forLog() == "waiting on 'build' (absent; 400 of the SCM's reports on the SHA were not read)"
        scm.allServeEvents.size() == 3
    }

    def "a listing without a total is one page"() {
        given:
        serve(STATUS, [state: 'pending', sha: SHA, statuses: [status(1, 'build', 'success')]])
        ScmStatusRig.noChecksApi(scm)

        when:
        def result = ScmStatusRig.executor(ScmStatusRig.connected(scm), ['build', 'lint']).start(ScmStatusRig.verdict())

        then:
        result.reason().forLog() == "waiting on 'lint' (absent)"
        scm.allServeEvents.size() == 2
    }

    private static List<Map<String, ?>> others(int count) {
        (1..count).collect { run(it, "other-${it}", 'completed', 'success') }
    }

    private static Map<String, ?> combined(int total, List<Map<String, ?>> entries) {
        [state: 'pending', sha: SHA, total_count: total, statuses: entries]
    }

    private void serve(String path, Map<String, ?> body) {
        scm.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader('Content-Type', 'application/json')
                .withBody(new ObjectMapper().writeValueAsString(body))))
    }
}
