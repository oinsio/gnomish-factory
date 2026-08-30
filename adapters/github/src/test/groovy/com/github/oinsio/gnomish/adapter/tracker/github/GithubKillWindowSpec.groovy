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
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

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
@Stepwise
class GithubKillWindowSpec extends Specification {

    private final GithubKillWindowWorld world = new GithubKillWindowWorld()

    /**
     * Every signature the sequences above actually froze, accumulated across all their windows so
     * the closing feature can check the enumeration in the other direction.
     */
    @Shared
    Set<String> observed = [] as Set

    def cleanup() {
        world.close()
    }

    def "FR19: every kill window of the #sequence sequence freezes a sweep-visible named shape"() {
        given: 'the sequence run once undisturbed, to learn how many writes it makes'
        int writes = countWrites(seed, act)

        expect: 'it makes exactly the writes this table says it does'
        writes == expectedWrites

        and: 'each of its windows leaves the task visible to the sweep, with enumerated facts'
        (0..<writes).each { int allowed ->
            def frozen = frozenFacts(seed, act, allowed)
            assert frozen != null:
            "${sequence} killed after write ${allowed}: the task left the sweep's listings"
            assert TrackerKillWindows.enumerates(frozen):
            "${sequence} killed after write ${allowed}: unenumerated facts ${TrackerKillWindows.signature(frozen)}"
            observed << TrackerKillWindows.signature(frozen)
        }

        where:
        sequence | expectedWrites | seed | act
        'claim' | 3 | { FixtureSeeder s, FixtureIssue i ->
            s.seedTask(i, new TrackerTaskState.Ready(), AbortFacts.none())
        } | { Tracker t, TaskRef r ->
            t.claim(r, GithubKillWindowWorld.KILLER)
        }
        'park' | 3 | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } | { Tracker t, TaskRef r ->
            t.park(r, ParkReason.ESCALATION, 'a human is needed')
        }
        'finish' | 3 | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } |
        { Tracker t, TaskRef r -> t.finish(r, 'all stages passed') }
        'abort' | 3 | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } | { Tracker t, TaskRef r ->
            t.recordAbort(r, new AbortRecord('durability broke',
            GithubKillWindowWorld.HOLDER, Instant.parse('2026-07-20T10:00:00Z'),
            RecoveryCause.INSTANCE_CRASH))
        }
        'reap' | 4 | { FixtureSeeder s, FixtureIssue i ->
            s.seedWorkingWithClaim(i, GithubKillWindowWorld.HOLDER)
        } | { Tracker t, TaskRef r ->
            t.removeStaleClaim(r, t.listOpen().find {
                it.ref() == r
            }.facts().claim())
        }
    }

    // FR19: the enumeration checked the other way round. The feature above proves every window this
    //     suite freezes is enumerated (frozen ⊆ SIGNATURES); without this one, a signature that
    //     stopped being produced — a sequence that lost a write, an adapter that stopped passing
    //     through a state — would sit in the fixture forever, still classified by
    //     TrackerKillWindowShapeSpec and no longer describing anything real. Both directions
    //     together are what make the shared list a contract instead of a wish. @Stepwise puts this
    //     last, after every row above has contributed what it froze.
    def "FR19: every enumerated window is one the sequences actually freeze"() {
        given: 'the signatures the fixture claims the five sequences produce'
        def enumerated = TrackerKillWindows.SIGNATURES as Set

        and: 'the fixture lists no window that stopped happening'
        def stale = enumerated - observed

        expect:
        assert stale.isEmpty():
        "TrackerKillWindows lists ${stale.size()} window(s) no sequence freezes any more: ${stale.sort()}"

        and: 'and the two sets are exactly each other — the forward direction is asserted per row'
        observed == enumerated
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
