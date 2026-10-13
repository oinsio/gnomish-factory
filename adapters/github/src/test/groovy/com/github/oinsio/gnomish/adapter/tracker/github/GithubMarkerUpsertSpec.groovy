package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import spock.lang.Specification

/**
 * Every factory comment the real {@link GithubTracker} writes, one kind per row of {@link
 * GithubMarkerWriteRows}, on the stateful fixture GitHub of {@link GithubKillWindowWorld}: each write
 * is killed right after its comment lands and re-driven, which only a find-then-upsert survives
 * without posting a second comment.
 *
 * <p>Implements FR11, UX3 of harden-task-branch-contract.
 */
class GithubMarkerUpsertSpec extends Specification {

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    // FR11, UX3: a write killed after its comment lands and re-driven finds that comment and updates it.
    // Scenario: tracker/github-tracker / Every marker kind is written through the upsert
    def "a #kind write killed after its comment and re-driven updates that comment, posting nothing"() {
        given:
        world.open(seed)
        world.holdTenure(new ClaimEpoch(4242))

        when: 'the write dies right after its comment landed, and the operation is re-driven'
        world.killAfter(1)
        world.attempt(act)
        world.noKill()
        world.resetLog()
        world.attempt(act)

        then: 'the re-drive posted no comment: it found the one it left and edited it'
        !world.writeLog().any { it ==~ /POST .*\/issues\/\d+\/comments/ }
        world.writeLog().any { it ==~ /PATCH .*\/issues\/comments\/\d+/ }
        GithubMarkerWriteRows.markersOf(world.issue(), kind).size() == 1

        where: 'the removal re-driven after such a kill is the pending feature of GithubTenureMarkersSpec (F79)'
        [kind, seed, act] << GithubMarkerWriteRows.rows().findAll {
            it[0] != GithubMarkerKind.STALE_CLAIM_REMOVED
        }
    }

    // The claim is the one write whose find is fused into its verify-read (design D6): it posts
    // first, then reads the thread back and deletes the duplicate it finds (GithubClaimLease's javadoc).
    // F78: scenario text aligned to what this test asserts
    // Scenario: tracker/github-tracker / Every marker kind is written through the upsert
    def "a claim killed after its comment and re-driven posts again, and its read-back deletes the duplicate"() {
        given:
        world.open { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
        }

        when: 'the claim dies after its label flip (two writes) and its comment (the third), and is re-driven'
        world.killAfter(3)
        world.attempt { Tracker t, TaskRef r ->
            t.claim(r, GithubKillWindowWorld.KILLER)
        }
        world.noKill()
        world.resetLog()
        world.attempt { Tracker t, TaskRef r ->
            t.claim(r, GithubKillWindowWorld.KILLER)
        }

        then: 'the re-drive posted its claim, and its verify-read deleted the duplicate, leaving one claim'
        world.writeLog().any { it ==~ /POST .*\/issues\/\d+\/comments/ }
        world.writeLog().any { it ==~ /DELETE .*\/issues\/comments\/\d+/ }
        GithubMarkerWriteRows.markersOf(world.issue(), GithubMarkerKind.CLAIM).size() == 1
    }
}
