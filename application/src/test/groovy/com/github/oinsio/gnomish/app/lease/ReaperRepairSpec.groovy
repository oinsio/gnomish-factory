package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Reaper, generalized to tracker-shape repair (FR19, FR12 of harden-task-branch-contract): the
 * sweep is the union of listReady and listOpen, every enumerated task is classified, and each
 * released shape is routed to the port operation its recovery names — stale-claim removal for a
 * footprint no tenure backs, index repair for a pending claim or a lagging index. A lagging index
 * is repaired on classification (its marker is already the truth); the two graced windows wait out
 * the window grace first.
 */
class ReaperRepairSpec extends Specification {

    private static final Duration TTL = Duration.ofMinutes(15)
    private static final Duration GRACE = Duration.ofMinutes(20)
    private static final TaskRef REF = new TaskRef('T-1')
    private static final ClaimVersion VERSION =
    new ClaimVersion('m-1', Instant.parse('2026-08-01T10:00:00Z'), new ClaimEpoch(9))

    private final Tracker tracker = Mock(Tracker)
    private final VirtualMonotonicTime time = new VirtualMonotonicTime()
    private final StalenessMemory memory = new StalenessMemory(time, TTL, GRACE)
    private final Reaper reaper = new Reaper(tracker, memory)

    // FR12: the claim sequence's kill window — working label, no claim comment — is rolled back to
    //     ready by the index repair, but only after the window grace has stood.
    def "a claim-pending window is repaired to ready after the grace, not before"() {
        given:
        tracker.listReady(_) >> []

        when: 'first tick observes the frozen window'
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [pending()]
        0 * tracker.repairIndex(_, _)

        when: 'the TTL passes but the longer window grace has not'
        time.advance(TTL)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [pending()]
        0 * tracker.repairIndex(_, _)

        when: 'the grace completes'
        time.advance(GRACE - TTL)
        reaper.reapOnce([])

        then: 'the repair is driven with the facts the sweep observed'
        1 * tracker.listOpen() >> [pending()]
        1 * tracker.repairIndex(REF, TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None())) >>
                new RepairIndexResult.Repaired(TrackerFacts.of(StateLabels.readyOnly()))
    }

    // FR12: a boundary marker under a still-working label is the truth waiting for its index — it
    //     is repaired on classification, with no grace to wait out.
    def "a lagging index is repaired on the tick that classifies it"() {
        when:
        tracker.listReady(_) >> []
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [lagging()]
        1 * tracker.repairIndex(REF, laggingFacts()) >>
                new RepairIndexResult.Repaired(TrackerFacts.of(StateLabels.deliveredOnly()))
        0 * tracker.removeStaleClaim(_, _)
    }

    // FR19: an abandoned footprint on a working task goes through the stale-claim removal, the same
    //     operation a dead tenure does — the port retires the footprint either way.
    def "an abandoned footprint is retired through the stale-claim removal after its grace"() {
        given:
        tracker.listReady(_) >> []

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [abandoned()]
        0 * tracker.removeStaleClaim(_, _)

        when:
        time.advance(GRACE)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> [abandoned()]
        1 * tracker.removeStaleClaim(REF, new ClaimFacts.Dead('inst-1')) >> new RemoveStaleClaimResult.Removed()
    }

    // FR19: the ghost claim that landed on a task already rolled back to ready reaches the sweep
    //     through the READY feed — the listing the old open-only sweep never looked at.
    def "a ghost claim on a ready-labeled task is swept through the ready feed and retired"() {
        given:
        def ghost = new ClaimFacts.Live('inst-1', VERSION)
        def entry = new ReadyTask(REF, AbortFacts.none(), false, false, 'title', ghost)

        when:
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> []
        1 * tracker.listReady(_) >> [entry]
        0 * tracker.removeStaleClaim(_, _)

        when:
        time.advance(GRACE)
        reaper.reapOnce([])

        then:
        1 * tracker.listOpen() >> []
        1 * tracker.listReady(_) >> [entry]
        1 * tracker.removeStaleClaim(REF, ghost) >> new RemoveStaleClaimResult.Removed()
    }

    // FR19, design D14: a repair that fails is re-armed, so the same unchanged shape is retried on
    //     a later tick instead of staying frozen until its facts change.
    def "a failed index repair is re-armed for the next tick"() {
        given: 'the same frozen window on every tick of this scenario'
        tracker.listReady(_) >> []
        tracker.listOpen() >> [pending()]
        reaper.reapOnce([])
        time.advance(GRACE)

        when: 'the repair fails with an infrastructure error'
        reaper.reapOnce([])

        then:
        1 * tracker.repairIndex(REF, _) >> {
            throw new RuntimeException('tracker down')
        }

        when: 'the next tick sees the very same unchanged shape'
        reaper.reapOnce([])

        then: 'the repair is retried rather than suppressed until the facts change'
        1 * tracker.repairIndex(REF, _) >> new RepairIndexResult.Repaired(TrackerFacts.of(StateLabels.readyOnly()))
    }

    // FR19: an Unchanged result is the converging no-op two reapers race into — never an error, and
    //     never a reason to re-arm the shape.
    def "an unchanged repair result is a converging no-op, not a failure"() {
        given: 'the frozen window, until the racing repair moves the facts on'
        tracker.listReady(_) >> []
        def listing = [pending()]
        tracker.listOpen() >> { listing }
        reaper.reapOnce([])
        time.advance(GRACE)

        when:
        reaper.reapOnce([])

        then: 'the converging no-op is not treated as an error'
        1 * tracker.repairIndex(REF, _) >>
                new RepairIndexResult.Unchanged(TrackerFacts.of(StateLabels.readyOnly()))

        when: 'the facts have since moved on, so the task leaves the sweep'
        listing = []
        reaper.reapOnce([])

        then: 'nothing is repaired and the memory has forgotten it'
        0 * tracker.repairIndex(_, _)
        memory.staleRefs().isEmpty()
    }

    // A parked task and a finished one are steady shapes: the sweep enumerates them and repairs
    //     nothing, however long they stand.
    def "steady shapes are never repaired, however long they stand"() {
        when:
        tracker.listReady(_) >> []
        reaper.reapOnce([])
        time.advance(GRACE.multipliedBy(10))
        reaper.reapOnce([])

        then:
        2 * tracker.listOpen() >> [parked()]
        0 * tracker.repairIndex(_, _)
        0 * tracker.removeStaleClaim(_, _)
    }

    // FR19: the sweep excludes this instance's own held claims from BOTH listings — a ready-feed
    //     entry for a task it holds is no more sweepable than an open-listing one, or the instance
    //     would repair the very tenure it is working.
    def "the sweep excludes the instance's own claims from the ready feed too"() {
        given: 'the ready feed carries a ghost claim on a task this instance itself holds'
        def own = new TaskRef('T-own')
        def ghost = new ClaimFacts.Live('inst-1', VERSION)
        tracker.listReady(_) >> [
            new ReadyTask(own, AbortFacts.none(), false, false, 'title', ghost)
        ]

        when:
        reaper.reapOnce([own])
        time.advance(GRACE)
        reaper.reapOnce([own])

        then: 'nothing is repaired, however long the shape stands'
        2 * tracker.listOpen() >> []
        0 * tracker.removeStaleClaim(_, _)
        0 * tracker.repairIndex(_, _)
    }

    // FR19: an adapter never omits a combination it cannot interpret, so a Foreign task really
    //     reaches the sweep. No recovery owner claims it, which makes surfacing it the sweep's
    //     whole duty — silence would leave an out-of-protocol task invisible to the operator.
    def "a foreign shape is surfaced with its diagnosis, and never repaired"() {
        given:
        tracker.listReady(_) >> []
        tracker.listOpen() >> [foreign()]

        when:
        def logged = capture {
            reaper.reapOnce([])
            time.advance(GRACE.multipliedBy(10))
            reaper.reapOnce([])
        }

        then: 'the diagnosis reaches the operator at warn'
        def warnings = logged.findAll { it.level == Level.WARN }
        warnings.any {
            it.formattedMessage.contains('T-1') && it.formattedMessage.contains('no gnomish state label present')
        }

        and: 'FR8 of harden-logging-observability: and it is findable by taskId like every reap line'
        warnings.every { it.MDCPropertyMap['taskId'] == 'T-1' }

        and: 'and no automatic repair touches it, nor is it latched as stale'
        0 * tracker.removeStaleClaim(_, _)
        0 * tracker.repairIndex(_, _)
        memory.staleRefs().isEmpty()
    }

    // No silent caps: a ready feed that fills the sweep's own page is logged, so a backlog deeper
    //     than one page is visible instead of looking like full coverage.
    // FR12 of harden-logging-observability: still logged, but at DEBUG — a full page is the
    //     normal shape of a busy backlog, and every tick of a busy factory would otherwise say so.
    def "a ready feed that fills the sweep page is logged at debug"() {
        given:
        def pagedTracker = Stub(Tracker) {
            listOpen() >> []
            listReady(_) >> readyPage(100)
        }
        def pagedReaper = new Reaper(pagedTracker, new StalenessMemory(time, TTL, GRACE))

        when:
        def logged = capture { pagedReaper.reapOnce([]) }

        then:
        def page = logged.find {
            it.formattedMessage.contains('filled the sweep page')
        }
        page.level == Level.DEBUG
    }

    def "a ready feed shorter than the sweep page is not logged"() {
        given:
        def pagedTracker = Stub(Tracker) {
            listOpen() >> []
            listReady(_) >> readyPage(99)
        }
        def pagedReaper = new Reaper(pagedTracker, new StalenessMemory(time, TTL, GRACE))

        when:
        def logged = capture { pagedReaper.reapOnce([]) }

        then:
        logged.every { !it.formattedMessage.contains('filled the sweep page') }
    }

    // The converging no-op is logged as one, and a real repair is not: an operator reading the log
    //     can tell a race that converged from a repair that actually flipped labels. At DEBUG
    //     (FR12): under contention convergence is the design working, not a degradation.
    def "an unchanged repair logs the converging line, a repaired one does not"() {
        given:
        tracker.listReady(_) >> []
        tracker.listOpen() >> [pending()]
        reaper.reapOnce([])
        time.advance(GRACE)

        when:
        def loggedOnUnchanged = capture {
            reaper.reapOnce([])
        }

        then:
        1 * tracker.repairIndex(REF, _) >> new RepairIndexResult.Unchanged(TrackerFacts.of(StateLabels.readyOnly()))
        def converged = loggedOnUnchanged.find {
            it.formattedMessage.contains('already moved')
        }
        converged.level == Level.DEBUG

        when: 'the re-armed shape is repaired for real on a later tick'
        memory.retryEmission(new TrackerRepair(REF, pending().facts(), new TrackerShape.ClaimPending()))
        def loggedOnRepaired = capture {
            reaper.reapOnce([])
        }

        then:
        1 * tracker.repairIndex(REF, _) >> new RepairIndexResult.Repaired(TrackerFacts.of(StateLabels.readyOnly()))
        loggedOnRepaired.every {
            !it.formattedMessage.contains('already moved')
        }
    }

    // FR8, UX2 of harden-logging-observability: the reaper's thread belongs to no task, so a reap
    //     decision would otherwise be unreachable from `grep taskId=<id>` — the one filter an
    //     operator reconstructing a task's story has. Every per-task line it writes carries the
    //     subject in the MDC, including the ones whose message already names it.
    def "FR8: every per-task reap line is findable by taskId"() {
        given:
        tracker.listReady(_) >> []
        tracker.listOpen() >> [pending()]
        reaper.reapOnce([])
        time.advance(GRACE)

        when: 'the window has stood out its grace and the repair converges against a racing writer'
        def logged = capture { reaper.reapOnce([]) }

        then:
        1 * tracker.repairIndex(REF, _) >> new RepairIndexResult.Unchanged(TrackerFacts.of(StateLabels.readyOnly()))

        and: 'the converging repair line names its task in the MDC, not only in its message'
        logged.find {
            it.formattedMessage.contains('already moved')
        }.MDCPropertyMap['taskId'] == 'T-1'
    }

    // FR8, UX2: the delta's own scenario — a stale-claim removal is the reap an operator asks
    //     about, and its converging no-op is the line that explains why nothing changed
    def "FR8: a stale-claim removal's line is findable by taskId"() {
        given:
        tracker.listReady(_) >> []
        tracker.listOpen() >> [abandoned()]
        reaper.reapOnce([])
        time.advance(GRACE)

        when: 'a racing reaper got there first, so this removal converges instead of removing'
        def logged = capture { reaper.reapOnce([]) }

        then:
        1 * tracker.removeStaleClaim(REF, _) >> new RemoveStaleClaimResult.Mismatch(VERSION)
        logged.find {
            it.formattedMessage.contains('already changed')
        }.MDCPropertyMap['taskId'] == 'T-1'
    }

    // FR8: the scope belongs to the per-task work alone — a sweep-wide line is about the estate
    def "FR8: a sweep-wide line carries no task scope"() {
        given:
        def pagedTracker = Stub(Tracker) {
            listOpen() >> []
            listReady(_) >> readyPage(100)
        }
        def pagedReaper = new Reaper(pagedTracker, new StalenessMemory(time, TTL, GRACE))

        when:
        def logged = capture { pagedReaper.reapOnce([]) }

        then:
        logged.find {
            it.formattedMessage.contains('filled the sweep page')
        }.MDCPropertyMap['taskId'] == null
    }

    // FR8: a repair that throws still reports under its task's scope — the failure WARN is the one
    //     line an operator most needs to find by taskId, and it is emitted from the catch
    def "FR8: a failed repair's warning is findable by taskId"() {
        given:
        tracker.listReady(_) >> []
        tracker.listOpen() >> [pending()]
        reaper.reapOnce([])
        time.advance(GRACE)

        when:
        def logged = capture { reaper.reapOnce([]) }

        then:
        1 * tracker.repairIndex(REF, _) >> {
            throw new IllegalStateException('tracker refused')
        }
        def failure = logged.find {
            it.formattedMessage.contains('repair failed')
        }
        failure.level == Level.WARN
        failure.MDCPropertyMap['taskId'] == 'T-1'
    }

    /**
     * Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec —
     * pinned at DEBUG, which is where the sweep's reconciliation chatter now lives (FR12).
     */
    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        def logs = LogCaptureSupport.attach(Reaper, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    private static List<ReadyTask> readyPage(int size) {
        (1..size).collect {
            new ReadyTask(new TaskRef("T-$it"), AbortFacts.none(), false, false, 'title', new ClaimFacts.None())
        }
    }

    private static OpenTask pending() {
        open(TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None()))
    }

    private static OpenTask abandoned() {
        open(TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.Dead('inst-1')))
    }

    private static OpenTask parked() {
        open(TrackerFacts.of(StateLabels.needsHumanOnly(), new ClaimFacts.Dead('inst-1')))
    }

    private static OpenTask foreign() {
        open(TrackerFacts.of(new StateLabels(false, false, false, false, false), new ClaimFacts.None()))
    }

    private static OpenTask lagging() {
        open(laggingFacts())
    }

    private static TrackerFacts laggingFacts() {
        new TrackerFacts(StateLabels.workingOnly(), new ClaimFacts.Dead('inst-1'), BoundaryKind.FINISH)
    }

    private static OpenTask open(TrackerFacts facts) {
        new OpenTask(REF, new TrackerTaskState.Working('inst-1'), facts.claim().liveVersion(), 'title', facts)
    }
}
