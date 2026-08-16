package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.TakeResult
import java.time.Duration
import spock.lang.Specification

/**
 * FR6 of add-claim-heartbeat (design D9): the {@code Working}-state takeover gate. A task held by
 * another instance is not a flat refusal but a pre-claim CONFIRMATION — the gate reads the claim
 * facts, asks the confirmation seam (unless {@code --takeover} already authorized it headlessly),
 * and only on confirmation returns the stale claim and falls through to the ordinary claim-and-
 * resume. Without confirmation it refuses, always naming the holder.
 *
 * <p>Driven entirely through ports (design D13(c) of split-into-modules): the tracker is scripted
 * and answers {@code Held} on the claim, so every confirmed path stops at the ordinary claim's own
 * refusal without touching a git port — which is precisely what proves the gate FELL THROUGH to
 * the ordinary claim rather than doing anything of its own.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class TakeTakeoverSpec extends Specification implements RunChainFakes {

    private static final String HOLDER = 'gnomish-other-99xxyy'

    private TaskGit git = new TaskGit(Stub(TaskStoreGit), Stub(TaskBranchGit), Stub(TaskWorktreeGit))

    /** A tracker whose queue reports {@code ref} with {@code version}, and which refuses every claim. */
    private Tracker trackerHolding(ClaimVersion version) {
        Stub(Tracker) {
            listOpen() >> (version == null
            ? []
            : [
                new OpenTask(REF, new TrackerTaskState.Working(HOLDER), version, 'title')
            ])
            claim(_, _) >> new ClaimResult.Held(HOLDER)
            // Scripted rather than left to Spock's default: the return type is a sealed interface,
            // which the stub cannot invent a value for.
            removeStaleClaim(_, _) >> new RemoveStaleClaimResult.Removed()
        }
    }

    private TakeResult take(TakeTakeover takeover, Tracker tracker) {
        takeover.take(CLONE_DIR, null, pipeline(), RunArguments.InteractiveMode.NONE, false,
                workingTask(HOLDER), tracker, INSTANCE, HOLDER)
    }

    // FR6: a declined confirmation refuses and changes NOTHING — no stale claim is returned, no
    // claim is attempted. The message names the holder so the operator knows who to talk to.
    def "a declined confirmation refuses, naming the holder, and returns no claim"() {
        given:
        def tracker = Mock(Tracker)
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, _age ->
            TakeoverConfirmation.Decision.DECLINED
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        def result = take(takeover, tracker)

        then: 'the gate read the claim facts...'
        1 * tracker.listOpen() >> []

        and: '...and then did nothing at all to the task'
        0 * tracker.removeStaleClaim(_, _)
        0 * tracker.claim(_, _)

        and:
        result instanceof TakeResult.Skipped
        result.reason().contains(HOLDER)
        result.reason().contains('declined')
    }

    // FR6: an UNAVAILABLE seam (no TTY — a headless run that did not pass --takeover) is a distinct
    // refusal from a human "no": it must point at the flag, since there is a way to proceed.
    def "an unavailable confirmation refuses and points at the headless flag"() {
        given:
        def tracker = trackerHolding(null)
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, _age ->
            TakeoverConfirmation.Decision.UNAVAILABLE
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        def result = take(takeover, tracker)

        then:
        result instanceof TakeResult.Skipped
        result.reason().contains(HOLDER)
        result.reason().contains('--takeover')
    }

    // FR6: a confirmed takeover returns the stale claim at the version it OBSERVED, then falls
    // through to the ordinary claim — which is what makes the confirmed-race handling the ordinary
    // claim's job (a re-claimed task comes back Held and is refused there, naming the holder).
    def "a confirmed takeover returns the observed stale claim and then claims by the ordinary lease"() {
        given:
        def observed = new ClaimVersion('marker-7', NOW.minusSeconds(90))
        def tracker = Mock(Tracker)
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, _age ->
            TakeoverConfirmation.Decision.CONFIRMED
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        def result = take(takeover, tracker)

        then: 'the version handed to removeStaleClaim is the one listOpen reported for THIS ref'
        1 * tracker.listOpen() >> [
            new OpenTask(REF, new TrackerTaskState.Working(HOLDER), observed, 'title')
        ]
        1 * tracker.removeStaleClaim(REF, observed)

        and: 'and the run then goes through the ordinary claim, whose own refusal it returns'
        1 * tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held(HOLDER)
        result instanceof TakeResult.Skipped
        result.reason().contains('refusing to take it.')
    }

    // FR6: with no observable claim version there is nothing to return — removeStaleClaim is
    // skipped entirely rather than called with a null, and the ordinary claim still runs.
    def "a confirmed takeover with no observable claim version skips the stale-claim removal"() {
        given:
        def tracker = Mock(Tracker)
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, _age ->
            TakeoverConfirmation.Decision.CONFIRMED
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        def result = take(takeover, tracker)

        then:
        1 * tracker.listOpen() >> []
        0 * tracker.removeStaleClaim(_, _)
        1 * tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held(HOLDER)
        result instanceof TakeResult.Skipped
    }

    // FR6: --takeover is a HEADLESS authorization — the confirmation seam is bypassed entirely, not
    // merely answered for. A seam that is consulted at all fails this scenario.
    def "the --takeover flag bypasses the confirmation seam entirely"() {
        given:
        def tracker = trackerHolding(new ClaimVersion('marker-7', NOW.minusSeconds(5)))
        def consulted = false
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, _age ->
            consulted = true
            TakeoverConfirmation.Decision.DECLINED
        } as TakeoverConfirmation,
        true, FIXED_CLOCK)

        when:
        def result = take(takeover, tracker)

        then: 'the seam was never asked, and the run proceeded to the ordinary claim'
        !consulted
        result instanceof TakeResult.Skipped
        result.reason().contains('refusing to take it.')
    }

    // FR6: the last-beat age shown to the operator is display-only human text on the run's wall
    // clock. It has its own scale boundaries — seconds under a minute, minutes under an hour, then
    // hours AND the leftover minutes — and an unobservable version reads as "unknown", never as 0s.
    def "renders the last-beat age the confirmation prompt shows"() {
        given:
        def version = beatAge == null ? null : new ClaimVersion('marker-7', NOW.minus(beatAge))
        def tracker = trackerHolding(version)
        String shown = null
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, age ->
            shown = age
            TakeoverConfirmation.Decision.DECLINED
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        take(takeover, tracker)

        then:
        shown == expected

        where:
        beatAge || expected
        null || 'unknown'
        Duration.ofSeconds(0) || '0s'
        Duration.ofSeconds(59) || '59s'
        Duration.ofSeconds(60) || '1m'
        Duration.ofMinutes(59) || '59m'
        Duration.ofMinutes(60) || '1h 0m'
        Duration.ofMinutes(125) || '2h 5m'
    }

    // FR6, design D2: the age is operator information, never a staleness DECISION — so a beat
    // timestamped in the future (clock skew between this instance and the holder's) is clamped to
    // 0s rather than rendering a negative age.
    def "clamps a future-dated beat to zero rather than rendering a negative age"() {
        given:
        def tracker = trackerHolding(new ClaimVersion('marker-7', NOW.plusSeconds(30)))
        String shown = null
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, age ->
            shown = age
            TakeoverConfirmation.Decision.DECLINED
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        take(takeover, tracker)

        then:
        shown == '0s'
    }

    // FR6: the claim facts are read for THIS ref. A queue holding other tasks' claims must not be
    // mistaken for this one's, so a version belonging to a different ref reads as unknown.
    def "ignores other tasks' claim versions when reading this ref's facts"() {
        given:
        def other = new TaskRef('github:o/r#999')
        def tracker = Mock(Tracker)
        String shown = null
        def takeover = new TakeTakeover(claimAndWork(git, tracker, Stub(RunAssembly)), { _ref, _holder, age ->
            shown = age
            TakeoverConfirmation.Decision.DECLINED
        } as TakeoverConfirmation,
        false, FIXED_CLOCK)

        when:
        take(takeover, tracker)

        then:
        1 * tracker.listOpen() >> [
            new OpenTask(other, new TrackerTaskState.Working('someone'), new ClaimVersion('m', NOW.minusSeconds(5)), 'other')
        ]
        shown == 'unknown'
    }
}
