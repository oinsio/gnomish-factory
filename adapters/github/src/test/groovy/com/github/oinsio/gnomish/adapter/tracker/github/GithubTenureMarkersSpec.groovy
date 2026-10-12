package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The boundary markers that end a tenure — the abort and the stale-claim removal — are scoped by the
 * tenure they end, so each tenure keeps its own comment: written by the real {@link GithubTracker}
 * over two real claims on the stateful fixture GitHub of {@link GithubKillWindowWorld}, whose
 * epoch book is {@link GithubKillWindowWorld#holdTenure}.
 *
 * <p>Implements FR11, FR13, UX3 of harden-task-branch-contract.
 */
class GithubTenureMarkersSpec extends Specification {

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    private void openReady() {
        world.open { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
        }
    }

    /** Claims the task as the world's own instance and holds the tenure the claim was issued. */
    private ClaimEpoch claimTenure() {
        def claim = world.tracker().claim(world.ref(), GithubKillWindowWorld.KILLER)
        assert claim instanceof ClaimResult.Acquired
        world.holdTenure(claim.epoch())
        claim.epoch()
    }

    private void abort(String cause) {
        world.tracker().recordAbort(world.ref(), new AbortRecord(UntrustedText.subprocess(cause),
                GithubKillWindowWorld.KILLER, Instant.parse('2026-07-20T10:00:00Z'), RecoveryCause.INSTANCE_CRASH))
    }

    private void removeObservedClaim() {
        world.attempt { Tracker t, TaskRef r ->
            t.removeStaleClaim(r, t.listOpen().find {
                it.ref() == r
            }.facts().claim())
        }
    }

    private List<FixtureIssue.FixtureComment> commentsOf(GithubMarkerKind kind) {
        world.issue().comments().findAll {
            GithubMarker.parse(it.body()).map { m ->
                m.kind()
            }.orElse(null) == kind
        }
    }

    private static String intentOf(FixtureIssue.FixtureComment comment) {
        GithubMarker.parse(comment.body()).get().identity().intent()
    }

    // FR11, FR13, UX3: one abort comment per tenure, each re-driven in place.
    // Scenario: tracker/github-tracker / Each tenure ends with its own abort comment
    def "an abort in each of two tenures is its own comment, and re-driving the first edits only the first"() {
        given:
        openReady()

        when: 'the task is aborted in one tenure, claimed again, and aborted in the next'
        def first = claimTenure()
        abort('first failure')
        def second = claimTenure()
        abort('second failure')
        def aborts = commentsOf(GithubMarkerKind.ABORT)

        then: 'the second abort is a new comment, scoped by its own tenure'
        aborts*.id().unique().size() == 2
        aborts.collect {
            intentOf(it)
        } == [
            "abort@${first.token()}",
            "abort@${second.token()}"
        ]*.toString()

        when: 'the first tenure re-drives its abort'
        world.holdTenure(first)
        world.resetLog()
        abort('first failure, re-driven')
        def after = commentsOf(GithubMarkerKind.ABORT)

        then: 'only the first tenure`s comment changed, in place'
        !world.writeLog().any { it ==~ /POST .*\/issues\/\d+\/comments/ }
        after*.id() == aborts*.id()
        after[0].body().contains('first failure, re-driven')
        after[1].body() == aborts[1].body()
    }

    // FR11, UX3: a removal is scoped by the epoch of the claim it removes.
    // Scenario: tracker/github-tracker / Each removed claim gets its own removal marker
    def "the stale claims of two tenures are removed into two markers, each scoped by its claim's epoch"() {
        given:
        openReady()

        when: 'a tenure dies and its claim is removed, then a second tenure dies and its claim is removed'
        def first = claimTenure()
        removeObservedClaim()
        def second = claimTenure()
        removeObservedClaim()
        def removals = commentsOf(GithubMarkerKind.STALE_CLAIM_REMOVED)

        then:
        removals*.id().unique().size() == 2
        removals.collect {
            intentOf(it)
        } == [first, second].collect {
            "stale_claim_removed@${it.token()}".toString()
        }
    }

    // FR11, UX3: re-driving a removal after its marker landed must update that marker. The marker is
    // itself a boundary, so the re-driven removal re-reads the claim as a dead footprint, scopes the
    // marker by the holder instead of the epoch, and posts a second comment.
    // Scenario: tracker/github-tracker / Each removed claim gets its own removal marker
    // Scenario: tracker/github-tracker / Every marker kind is written through the upsert
    def "a removal killed after its marker and re-driven updates its own marker"() {
        given:
        openReady()
        claimTenure()

        when: 'the removal dies right after its marker, and the sweep re-drives it'
        world.killAfter(1)
        removeObservedClaim()
        world.noKill()
        world.resetLog()
        removeObservedClaim()

        then:
        commentsOf(GithubMarkerKind.STALE_CLAIM_REMOVED).size() == 1
        !world.writeLog().any { it ==~ /POST .*\/issues\/\d+\/comments/ }
    }
}
