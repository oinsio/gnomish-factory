package com.github.oinsio.gnomish.adapter.check.github

import com.github.oinsio.gnomish.domain.engine.PollStatus
import spock.lang.Specification

/**
 * GithubWorkflowRunVerdict (FR2 of add-external-check-github-actions): pure
 * mapping from a matching run (or its absence) to a PollStatus — success
 * conclusion -> Pass, any other/unknown conclusion -> Fail (fail-closed),
 * no match or no conclusion yet -> Running.
 *
 * Implements FR2 of add-external-check-github-actions.
 */
class GithubWorkflowRunVerdictSpec extends Specification {

    private static GithubWorkflowRun runWithConclusion(String conclusion) {
        new GithubWorkflowRun(1L, 'abc123', 'ci.yml', 1, 'completed', conclusion, null)
    }

    def "maps a matching run's conclusion to the corresponding PollStatus"() {
        given:
        def matchingRun = Optional.of(runWithConclusion(conclusion))

        when:
        def status = GithubWorkflowRunVerdict.fromMatchingRun(matchingRun)

        then:
        status.class == expectedType

        where:
        conclusion | expectedType
        'success' | PollStatus.Pass
        'failure' | PollStatus.Fail
        'cancelled' | PollStatus.Fail
        'timed_out' | PollStatus.Fail
        'action_required' | PollStatus.Fail
        'some_future_conclusion_this_adapter_does_not_recognize' | PollStatus.Fail
    }

    def "a Pass carries the authoritative run's platform URL"() {
        given: 'NFR-O2 of add-sandbox-core: a green check is auditable from the tracker'
        def run = new GithubWorkflowRun(
                1L, 'abc123', 'ci.yml', 1, 'completed', 'success', 'https://github.com/acme/widgets/actions/runs/1')

        when:
        def status = GithubWorkflowRunVerdict.fromMatchingRun(Optional.of(run))

        then:
        status == new PollStatus.Pass('https://github.com/acme/widgets/actions/runs/1')
    }

    def "a Pass of a run without a platform URL carries none"() {
        when:
        def status = GithubWorkflowRunVerdict.fromMatchingRun(Optional.of(runWithConclusion('success')))

        then:
        status == new PollStatus.Pass()
    }

    def "a Fail verdict carries no findings yet (task 4.1 populates them)"() {
        given:
        def matchingRun = Optional.of(runWithConclusion('failure'))

        when:
        def status = GithubWorkflowRunVerdict.fromMatchingRun(matchingRun)

        then:
        (status as PollStatus.Fail).findings() == []
    }

    def "a run without a conclusion yet reads as Running"() {
        given:
        def matchingRun = Optional.of(runWithConclusion(null))

        when:
        def status = GithubWorkflowRunVerdict.fromMatchingRun(matchingRun)

        then:
        status instanceof PollStatus.Running
    }

    def "no matching run reads as Running"() {
        when:
        def status = GithubWorkflowRunVerdict.fromMatchingRun(Optional.empty())

        then:
        status instanceof PollStatus.Running
    }
}
