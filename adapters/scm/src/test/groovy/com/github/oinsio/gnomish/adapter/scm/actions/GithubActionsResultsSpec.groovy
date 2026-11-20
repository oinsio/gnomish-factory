package com.github.oinsio.gnomish.adapter.scm.actions

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR6, NFR-C1 of migrate-verify-to-executor-contract, design D5, D13: the runs judgement rendered as
 * the executor contract's result — each poll status to its status, a failed job to an {@code error}
 * result that carries the finding's details apart from its message (design D19), a green run's link
 * carried on the SARIF run (design D23), and a known-zero usage.
 */
class GithubActionsResultsSpec extends Specification {

    def "#outcome renders as #status"() {
        when:
        def result = GithubActionsResults.of(poll, 'abc123')

        then:
        result.status() == status
        result.usage().provenance() == UsageProvenance.REPORTED
        result.usage().models().isEmpty()

        where:
        outcome | poll || status
        'a green run' | new PollStatus.Pass('https://run') || Status.COMPLETED
        'a red run' | new PollStatus.Fail([]) || Status.COMPLETED
        'a run in progress' | new PollStatus.Running() || Status.PENDING
        'a run no verdict was read from' | new PollStatus.CannotVerify(UntrustedText.factory('down'), UntrustedText.factory('')) || Status.FAILED
    }

    def "a run no verdict was read from is infrastructure, its reason the judgement's"() {
        when:
        def result = GithubActionsResults.of(new PollStatus.CannotVerify(UntrustedText.factory('platform down'),
                UntrustedText.factory('trace')), 'abc123')

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().forLog() == 'platform down'
    }

    // D19: the details travel as the SARIF result's own details, apart from the message.
    def "a failed job's details travel beside its message, and a job with none carries none: #why"() {
        when:
        def results = GithubActionsRig.results(GithubActionsResults.of(new PollStatus.Fail([finding]), 'abc123'))

        then:
        results.size() == 1
        results[0].level() == SarifLevel.ERROR
        results[0].message().forLog() == 'job failed'
        results[0].details()?.forLog() == details
        results[0].uri()?.forLog() == location

        where:
        why | finding || details | location
        'details' | new Finding('job failed', 'build', 'the tail') || 'the tail' | 'build'
        'no details' | new Finding('job failed', null, null) || null | null
        'blank details' | new Finding('job failed', 'lint', '  ') || null | 'lint'
    }

    // D23: a green run's link crosses the contract as the run's properties.runUrl, minted tracker.
    def "a green run writes its run URL as the SARIF run's properties.runUrl, and a pass without one writes none"() {
        expect:
        def run = GithubActionsResults.of(new PollStatus.Pass(url), 'abc123').answer().path('sarif').path('runs')[0]
        run.path('results').isEmpty()
        run.has('properties') == (url != null)
        run.path('properties').path('runUrl').textValue() == url

        where:
        url << [
            'https://github.example/acme/widgets/actions/runs/1',
            null
        ]
    }
}
