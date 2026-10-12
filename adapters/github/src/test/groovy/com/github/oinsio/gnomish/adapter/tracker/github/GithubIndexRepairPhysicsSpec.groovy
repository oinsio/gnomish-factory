package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import spock.lang.Specification

/**
 * The physics of {@code repairIndex} across process deaths and concurrent repairers, on the
 * stateful fixture GitHub of {@link GithubKillWindowWorld}: what a kill after the repair marker
 * freezes, what the repair marker does to the claim facts, and how two repairs of one observed shape
 * end. The single-repair behavior — which flip each boundary implies, the marker-first order, the
 * moved-observation no-op — is {@link GithubIndexRepairSpec}'s.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
class GithubIndexRepairPhysicsSpec extends Specification {

    /** Write 1 of a repair is its marker; the label flips come after it. */
    private static final int REPAIR_MARKER_ONLY = 1

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    /** A working issue whose claim is followed by a {@code kind} boundary: the index lags behind it. */
    private static Closure lagging(GithubMarkerKind kind, String reason = null) {
        { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
            s.postMarker(i, kind, GithubKillWindowWorld.HOLDER, reason, 'boundary')
        }
    }

    /** A working issue with no claim footprint at all: a claim that never posted its comment. */
    private static Closure claimPending() { {
            FixtureSeeder s, FixtureIssue i ->
            i.addLabel(FixtureSeeder.WORKING_LABEL)
        }
    }

    // FR19, FR12: the repair marker implies no state, so a kill after it freezes the observed shape.
    // Scenario: tracker/github-tracker / Killed repair stays inside the sweep universe
    def "a repair killed after its marker freezes the #shape it was repairing, and the next tick repairs it"() {
        given:
        world.open(seed)
        def observed = world.sweepFacts()

        when: 'the repairing instance dies after the repair marker, before any label flip'
        world.killAfter(REPAIR_MARKER_ONLY)
        world.attempt { t, r -> t.repairIndex(r, observed) }
        world.noKill()
        def frozen = world.sweepFacts()

        then: 'the repair marker was written, and the sweep still reads the very facts it repaired'
        world.issue().comments().any {
            GithubMarker.parse(it.body()).map { m ->
                m.kind()
            }.orElse(null) == GithubMarkerKind.INDEX_REPAIR
        }
        frozen == observed

        when: 'a later tick runs the same repair on what it now observes'
        def again = world.tracker().repairIndex(world.ref(), frozen)

        then:
        again instanceof RepairIndexResult.Repaired
        world.issue().labels() == [FixtureSeeder.READY_LABEL]

        where:
        shape | seed
        'index lagging' | lagging(GithubMarkerKind.ABORT)
        'claim pending' | claimPending()
    }

    // FR19: INDEX_REPAIR is not a claim boundary — it neither displaces the boundary nor voids a claim.
    // Scenario: tracker/github-tracker / The repair marker is not a claim boundary
    def "a repair marker after a #boundary boundary leaves that boundary the latest and anchors no claim race"() {
        given:
        world.open(lagging(kind, reason))
        def observed = world.sweepFacts()
        assert observed.latestBoundary() == boundary

        when: 'the repair marker lands after the boundary, and the claim facts are resolved again'
        world.killAfter(REPAIR_MARKER_ONLY)
        world.attempt { t, r -> t.repairIndex(r, observed) }
        world.noKill()
        def resolved = world.sweepFacts()

        then: 'the boundary the repair was completing is still the latest, with the same claim facts'
        resolved.latestBoundary() == boundary
        resolved.claim() == observed.claim()

        when: 'the repair completes and another instance claims the task'
        world.tracker().repairIndex(world.ref(), resolved)
        world.issue().removeLabel(FixtureSeeder.NEEDS_HUMAN_LABEL)
        world.issue().addLabel(FixtureSeeder.READY_LABEL)
        def claim = world.tracker().claim(world.ref(), GithubKillWindowWorld.KILLER)

        then: 'the claim race is decided by the claims alone: the newcomer holds the task'
        claim instanceof ClaimResult.Acquired
        world.tracker().fetchTask(world.ref()).state() == new TrackerTaskState.Working(GithubKillWindowWorld.KILLER)

        where:
        boundary | kind | reason
        BoundaryKind.ABORT | GithubMarkerKind.ABORT | null
        BoundaryKind.PARK | GithubMarkerKind.PARK | 'escalation'
    }

    // FR19, FR12: two repairers of one shape both finish, and the labels end where the truth implies.
    // Scenario: tracker/github-tracker / Racing repairs converge
    def "two concurrent repairs of the same observed #shape both succeed and leave the #target label"() {
        given:
        world.open(seed)
        def observed = world.sweepFacts()
        def start = new CountDownLatch(1)
        def results = new CopyOnWriteArrayList()
        def failures = new CopyOnWriteArrayList<Throwable>()
        def repairers = (1..2).collect {
            Thread.start {
                start.await()
                try {
                    results << world.tracker().repairIndex(world.ref(), observed)
                } catch (Throwable e) {
                    failures << e
                }
            }
        }

        when:
        start.countDown()
        repairers*.join()

        then: 'neither repair failed'
        failures.isEmpty()
        results.size() == 2
        results.every {
            it instanceof RepairIndexResult.Repaired || it instanceof RepairIndexResult.Unchanged
        }

        and: 'the issue wears exactly the label its recorded truth implies'
        world.issue().labels() == [target]

        where:
        shape | seed | target
        'index lagging' | lagging(GithubMarkerKind.PARK, 'escalation') | FixtureSeeder.NEEDS_HUMAN_LABEL
        'claim pending' | claimPending() | FixtureSeeder.READY_LABEL
    }
}
