package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification

/**
 * GithubTrackerFacts (FR19, FR12 of harden-task-branch-contract, design D16): the adapter's whole
 * share of the tracker-shape work is reporting. Over every label set and every short marker thread
 * it reports the labels as they are, the claim footprint and the boundary after it, and decides
 * nothing — no shape, no staleness, no grace: it has no time input at all, so a claim whose last
 * update is decades old is still the live footprint it is, and judging it is core's.
 */
class GithubTrackerFactsSpec extends Specification {

    private static final List<GithubMarkerKind> KINDS = [
        GithubMarkerKind.CLAIM,
        GithubMarkerKind.ABORT,
        GithubMarkerKind.PARK,
        GithubMarkerKind.FINISH,
        GithubMarkerKind.STALE_CLAIM_REMOVED,
        GithubMarkerKind.NOTE,
    ]

    private static final Instant AT = Instant.parse('2026-08-01T10:00:00Z')

    // FR19: no combination is omitted, reinterpreted or judged by the adapter
    // Scenario: lifecycle/claim-heartbeat / An adapter reports every combination as facts
    def "FR19: every label set and every marker thread is reported as its facts, none omitted or reinterpreted"() {
        given: 'all 32 label sets and every thread of up to three markers'
        def labelSets = (0..<32).collect { int bits ->
            new StateLabels((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, (bits & 16) != 0)
        }
        def threads = [[]] + (1..3).collectMany { int length ->
            sequences(length)
        }

        expect: 'each combination reports its own labels, and the footprint and boundary the thread holds'
        labelSets.every { labels ->
            threads.every { List<GithubMarkerKind> kinds ->
                def comments = thread(kinds)
                def facts = GithubTrackerFacts.of(labels, comments)
                facts.labels() == labels && facts.claim() == expectedClaim(comments) &&
                        facts.latestBoundary() == expectedBoundary(kinds)
            }
        }
    }

    // FR2 of add-claim-heartbeat: staleness is timed in core, on the observer's own clock
    // Scenario: lifecycle/claim-heartbeat / Core times the claims, adapters only report facts
    def "FR2: a claim decades old is reported as the live footprint it is, and the derivation has no time input"() {
        given:
        def ancient = Instant.parse('2000-01-01T00:00:00Z')
        def comments = [
            candidate(501L, ancient, GithubMarkerKind.CLAIM, 'inst-a')
        ]

        when:
        def facts = GithubTrackerFacts.of(StateLabels.workingOnly(), comments)

        then: 'live, with the version exactly as the server stamped it'
        facts.claim() == new ClaimFacts.Live('inst-a', GithubClaimComment.versionOf(501L, ancient))

        and: 'no method of the derivation takes, and no field holds, a clock or a duration'
        def timeTypes = [
            InstantSource,
            Duration,
            java.time.Clock
        ]
        GithubTrackerFacts.declaredMethods.every { method ->
            method.parameterTypes.every { type ->
                !timeTypes.any {
                    it.isAssignableFrom(type)
                }
            }
        }
        GithubTrackerFacts.declaredFields.findAll {
            !Modifier.isStatic(it.modifiers)
        }.isEmpty()
    }

    // FR13 of harden-task-branch-contract: a tracker write stamped by an earlier tenure is no
    //     ground for staleness — the facts read over it are the facts of the same thread unstamped
    // Scenario: tracker/tracker-port / An older token never marks an artifact stale
    def "FR13: markers stamped with an older tenure's epoch report the same facts as unstamped ones"() {
        given: 'tenure 500 aborted, tenure 700 claimed; a note after the reclaim still carries epoch 500'
        def stamped = [
            stampedCandidate(500L, GithubMarkerKind.CLAIM, null),
            stampedCandidate(501L, GithubMarkerKind.ABORT, new ClaimEpoch(500)),
            stampedCandidate(700L, GithubMarkerKind.CLAIM, null),
            stampedCandidate(701L, GithubMarkerKind.NOTE, new ClaimEpoch(500)),
        ]
        def unstamped = stamped.collect {
            stampedCandidate(it.id(), it.marker().kind(), null)
        }

        when:
        def facts = GithubTrackerFacts.of(StateLabels.workingOnly(), stamped)

        then: 'the current tenure is the live footprint, and nothing reads as stale'
        facts.claim() == new ClaimFacts.Live('inst-700', GithubClaimComment.versionOf(700L, AT))
        facts.latestBoundary() == null
        facts == GithubTrackerFacts.of(StateLabels.workingOnly(), unstamped)
    }

    private static GithubClaimComment.Candidate stampedCandidate(long id, GithubMarkerKind kind, ClaimEpoch epoch) {
        new GithubClaimComment.Candidate(id, AT,
                new ParsedMarker(kind, "inst-${id}", AT, 1, UntrustedText.tracker(''), null, null, epoch))
    }

    private static List<List<GithubMarkerKind>> sequences(int length) {
        length == 0 ? [[]] : sequences(length - 1).collectMany { prefix ->
            KINDS.collect {
                prefix + [it]
            }
        }
    }

    /** A thread in posting order: ids ascend with position, each claim by its own instance. */
    private static List<GithubClaimComment.Candidate> thread(List<GithubMarkerKind> kinds) {
        kinds.withIndex().collect { kind, int i ->
            candidate(100L + i, AT.plusSeconds(i), kind, "inst-${i}")
        }
    }

    private static GithubClaimComment.Candidate candidate(long id, Instant at, GithubMarkerKind kind, String instance) {
        new GithubClaimComment.Candidate(id, at,
                new ParsedMarker(kind, instance, at, 1, UntrustedText.tracker(''), null, null, null))
    }

    private static boolean isBoundary(GithubMarkerKind kind) {
        kind in [
            GithubMarkerKind.ABORT,
            GithubMarkerKind.PARK,
            GithubMarkerKind.FINISH,
            GithubMarkerKind.STALE_CLAIM_REMOVED
        ]
    }

    /** The earliest claim after the newest boundary is live; else the last claim is a dead footprint. */
    private static ClaimFacts expectedClaim(List<GithubClaimComment.Candidate> comments) {
        int boundary = comments.findLastIndexOf {
            isBoundary(it.marker().kind())
        }
        def claims = comments.findAll {
            it.marker().kind() == GithubMarkerKind.CLAIM
        }
        def live = comments.drop(boundary + 1).find {
            it.marker().kind() == GithubMarkerKind.CLAIM
        }
        if (live != null) {
            return new ClaimFacts.Live(live.marker().instance(), GithubClaimComment.versionOf(live.id(), live.updatedAt()))
        }
        claims ? new ClaimFacts.Dead(claims.last().marker().instance()) : new ClaimFacts.None()
    }

    /** The newest boundary, when it comes after the newest claim. */
    private static BoundaryKind expectedBoundary(List<GithubMarkerKind> kinds) {
        int boundary = kinds.findLastIndexOf { isBoundary(it) }
        int claim = kinds.findLastIndexOf { it == GithubMarkerKind.CLAIM }
        boundary >= 0 && boundary> claim ? BoundaryKind.valueOf(kinds[boundary].name()) : null
    }
}
