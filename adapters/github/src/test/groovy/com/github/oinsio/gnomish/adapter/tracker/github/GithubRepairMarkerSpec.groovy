package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import spock.lang.Specification

/**
 * The repair marker of the GitHub adapter names the shape the repair observed (FR12 of
 * harden-task-branch-contract), read back from the issue thread the real adapter wrote to: a
 * claimless working issue is a pending claim, a working label under a boundary marker is an index
 * lagging behind that marker.
 */
class GithubRepairMarkerSpec extends Specification {

    private GithubContractTracker github = new GithubContractTracker()

    def cleanup() {
        github.stop()
    }

    // FR12: the structural repair marker names the observed shape — ClaimPending or IndexLagging
    // Scenario: tracker/tracker-port / The repair marker names the observed shape
    def "the repair marker of a #shape repair names that shape"() {
        given:
        def ref = new TaskRef("fixture:repair-marker-${shape}")
        shape == 'claim-pending' ? claimless(ref) : finishedUnderWorkingLabel(ref)
        def observed = github.adapter.listOpen().find {
            it.ref() == ref
        }.facts()

        when:
        def result = github.adapter.repairIndex(ref, observed)

        then:
        result instanceof RepairIndexResult.Repaired
        github.adapter.postedTexts(ref).last().contains('index_repair')
        github.adapter.postedTexts(ref).last().contains(phrase)

        where:
        shape || phrase
        'claim-pending' || 'claim pending'
        'index-lagging' || 'index lagging behind the finish marker'
    }

    private void claimless(TaskRef ref) {
        github.adapter.seedWorkingWithoutClaim(ref)
    }

    private void finishedUnderWorkingLabel(TaskRef ref) {
        github.adapter.seedWorkingWithClaim(ref, GithubContractTracker.HOLDER_INSTANCE_ID)
        github.adapter.finish(ref, 'summary: shipped it')
        github.rewindIndexToWorking(ref)
    }
}
