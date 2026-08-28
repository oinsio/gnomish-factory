package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerKillWindows
import java.time.Instant
import spock.lang.Specification

/**
 * The tracker half of the kill-point harness (task 9.1b, FR19, M1 of harden-task-branch-contract):
 * the in-memory reference adapter's writes are atomic, so the windows of a multi-write tracker
 * sequence can only be frozen against an adapter whose writes physically are not. This suite fails
 * the connection after each write of the claim, park, finish, abort and reap sequences and asserts
 * what the next sweep sees.
 *
 * <p>Two properties per window, and they are the whole point: the task stays inside the sweep's own
 * listing universe ({@code listReady} ∪ {@code listOpen}), and the facts it reports there are one of
 * the combinations {@link TrackerKillWindows} enumerates. That fixture is where this suite and the
 * classification half meet: {@code TrackerKillWindowShapeSpec} in the composition layer classifies
 * exactly those combinations, because {@code TrackerShapeClassifier} lives in a module this vendor
 * bundle must not depend on (FR2 of split-into-modules). A window this suite freezes that the
 * fixture does not list fails here; a combination the fixture lists that classifies to {@code
 * Foreign} — or to a shape no retry and no sweep owns — fails there.
 *
 * <p>Implements FR19 of harden-task-branch-contract.
 */
class GithubKillWindowSpec extends Specification {

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    def cleanup() {
        world.close()
    }

    def "FR19: every kill window of the #sequence sequence freezes a sweep-visible named shape"() {
        given: 'the sequence run once undisturbed, to learn how many writes it makes'
        int writes = countWrites(seed, act)

        expect: 'each of its windows leaves the task visible to the sweep, with enumerated facts'
        writes> 1
        (0..<writes).each { int allowed ->
            def frozen = frozenFacts(seed, act, allowed)
            assert frozen != null:
            "${sequence} killed after write ${allowed}: the task left the sweep's listings"
            assert TrackerKillWindows.enumerates(frozen):
            "${sequence} killed after write ${allowed}: unenumerated facts ${TrackerKillWindows.signature(frozen)}"
        }

        where:
        sequence | seed | act
        'claim' | { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
        } | { Tracker t, TaskRef r ->
            t.claim(r, GithubKillWindowWorld.KILLER)
        }
        'park' | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } | { Tracker t, TaskRef r ->
            t.park(r, ParkReason.ESCALATION, 'a human is needed')
        }
        'finish' | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } |
        { Tracker t, TaskRef r -> t.finish(r, 'all stages passed') }
        'abort' | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } | { Tracker t, TaskRef r ->
            t.recordAbort(r, new AbortRecord('durability broke',
            GithubKillWindowWorld.HOLDER, Instant.parse('2026-07-20T10:00:00Z'),
            RecoveryCause.INSTANCE_CRASH))
        }
        'reap' | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } | { Tracker t, TaskRef r ->
            t.removeStaleClaim(r, t.listOpen().find {
                it.ref() == r
            }.facts().claim())
        }
    }

    /** Runs the sequence undisturbed and reports how many mutating requests it made. */
    private int countWrites(Closure seed, Closure act) {
        world.open(seed)
        world.noKill()
        world.attempt(act)
        world.writesSeen()
    }

    /** Runs the sequence with the connection failing past {@code allowed} writes, then reads the facts. */
    private TrackerFacts frozenFacts(Closure seed, Closure act, int allowed) {
        world.open(seed)
        world.killAfter(allowed)
        world.attempt(act)
        world.noKill()
        world.sweepFacts()
    }
}
