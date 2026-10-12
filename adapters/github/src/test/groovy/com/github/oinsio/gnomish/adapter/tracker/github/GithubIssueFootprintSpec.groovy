package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * What one operation of the real {@link GithubTracker} leaves on a real-shaped issue — its labels,
 * its thread, and the writes it costs — observed on the stateful fixture GitHub of {@link
 * GithubKillWindowWorld} with no kill armed.
 *
 * <p>Implements FR5, FR7 of add-tracker-port; FR1 of add-claim-heartbeat; FR7, NFR-P1 of
 * add-factory-serve; FR3 of fix-abort-progress-reset.
 */
class GithubIssueFootprintSpec extends Specification {

    private static final List<String> STATE_LABELS = [
        FixtureSeeder.READY_LABEL,
        FixtureSeeder.WORKING_LABEL,
        FixtureSeeder.NEEDS_HUMAN_LABEL,
        FixtureSeeder.DELIVERED_LABEL
    ]

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    // FR5 of add-tracker-port: a human's label edit is the return; the adapter reads it as Ready.
    // Scenario: tracker/github-tracker / Human return is visible
    def "a parked issue a human moves back to ready is fetched as Ready"() {
        given: 'a parked issue: the needs-human label and a park marker'
        world.open { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())
        }
        assert world.tracker().fetchTask(world.ref()).state() instanceof TrackerTaskState.AwaitingHuman

        when: 'a human swaps the needs-human label for the ready label in the GitHub UI'
        world.issue().removeLabel(FixtureSeeder.NEEDS_HUMAN_LABEL)
        world.issue().addLabel(FixtureSeeder.READY_LABEL)

        then:
        world.tracker().fetchTask(world.ref()).state() instanceof TrackerTaskState.Ready
    }

    // FR5, FR7 of add-tracker-port: labels index the state; who holds, aborted or acknowledged
    //     lives only in the structural comments.
    // Scenario: tracker/github-tracker / Coordination facts never ride on labels
    def "claim, abort and acknowledge change the labels only by state transitions"() {
        given: 'a ready issue carrying a human reply to acknowledge later'
        world.open { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
            s.seedReply(i, new HumanReply('go with plan B', Instant.parse('2000-06-01T00:00:00Z')))
        }
        def tracker = world.tracker()
        def ref = world.ref()
        def labelsAfter = [:]

        when:
        assert tracker.claim(ref, GithubKillWindowWorld.KILLER) instanceof ClaimResult.Acquired
        labelsAfter.claim = world.issue().labels()
        tracker.recordAbort(ref, new AbortRecord(UntrustedText.subprocess('tests failed'),
                GithubKillWindowWorld.KILLER, Instant.parse('2026-07-20T10:00:00Z'), RecoveryCause.INSTANCE_CRASH))
        labelsAfter.abort = world.issue().labels()
        world.resetLog()
        tracker.acknowledgeDecision(ref, 'go with plan B')
        labelsAfter.ack = world.issue().labels()

        then: 'the labels are exactly the state each step implies'
        labelsAfter == [claim: [FixtureSeeder.WORKING_LABEL], abort: [FixtureSeeder.READY_LABEL],
            ack: [FixtureSeeder.READY_LABEL]]

        and: 'the acknowledge touched no label at all'
        world.writeLog().every { !it.contains('/labels') }

        and: 'the holder, the abort and the acknowledge are structural comments'
        def kinds = markers().collect { it.kind() }
        kinds.containsAll([
            GithubMarkerKind.CLAIM,
            GithubMarkerKind.ABORT,
            GithubMarkerKind.ACK
        ])
        markers().find {
            it.kind() == GithubMarkerKind.CLAIM
        }.instance() == GithubKillWindowWorld.KILLER

        and: 'no label names any of them'
        world.issue().labels().every { it in STATE_LABELS }
    }

    // FR1 of add-claim-heartbeat: the beat edits the claim comment in place — one write.
    // Scenario: tracker/github-tracker / A beat costs one write
    def "a beat issues exactly one write, the PATCH of the claim comment"() {
        given:
        world.open { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.KILLER)
        }
        def claimId = world.issue().comments().first().id()
        world.resetLog()

        when:
        def result = world.tracker().heartbeat(world.ref(), 'stage build, attempt 1')

        then:
        result instanceof HeartbeatResult.Beaten
        world.writeLog() == [
            "PATCH /repos/acme/widgets/issues/comments/${claimId}".toString()
        ]
    }

    // FR7, NFR-P1 of add-factory-serve: the returned fact is a read of the markers already there.
    // Scenario: tracker/github-tracker / No new artifacts
    def "computing the returned fact for a listing writes nothing to the issue"() {
        given: 'a parked issue a human returned to ready'
        world.open { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())
        }
        world.issue().removeLabel(FixtureSeeder.NEEDS_HUMAN_LABEL)
        world.issue().addLabel(FixtureSeeder.READY_LABEL)
        def threadBefore = world.issue().comments()
        def labelsBefore = world.issue().labels()
        world.resetLog()

        when:
        def entry = world.tracker().listReady(50).find {
            it.ref() == world.ref()
        }

        then: 'the fact is derived'
        entry.returned()

        and: 'and nothing was written to get it'
        world.writeLog().isEmpty()
        world.issue().comments() == threadBefore
        world.issue().labels() == labelsBefore
    }

    // FR3 of fix-abort-progress-reset: both readers count only the aborts after the latest progress.
    // Scenario: tracker/github-tracker / fetchTask reader resets on progress
    def "fetchTask counts one abort for claim, abort, progress, abort — the feed reader's count"() {
        given: 'a ready issue whose thread reads claim, abort, progress, abort'
        world.open { FixtureSeeder s, FixtureIssue i ->
            i.addLabel(FixtureSeeder.READY_LABEL)
            s.postMarker(i, GithubMarkerKind.CLAIM, GithubKillWindowWorld.HOLDER, null, 'claimed')
            s.postMarker(i, GithubMarkerKind.ABORT, GithubKillWindowWorld.HOLDER, null, 'aborted')
            s.postMarker(i, GithubMarkerKind.PROGRESS, GithubKillWindowWorld.HOLDER, null, 'progress')
            s.postMarker(i, GithubMarkerKind.ABORT, GithubKillWindowWorld.HOLDER, null, 'aborted again')
        }

        when:
        def fetched = world.tracker().fetchTask(world.ref()).abortFacts()
        def listed = world.tracker().listReady(50).find {
            it.ref() == world.ref()
        }.abortFacts()

        then:
        fetched.count() == 1
        fetched == listed
    }

    private List<ParsedMarker> markers() {
        world.issue().comments().collect {
            GithubMarker.parse(it.body())
        }.findAll {
            it.present
        }*.get()
    }
}
