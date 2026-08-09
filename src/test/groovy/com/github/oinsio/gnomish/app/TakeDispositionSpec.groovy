package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.DivergedBranchException
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * FR9, UX2 of add-tracker-port (task 5.9): {@link TakeDisposition#dispose} — the explicit-mode
 * {@code take <ref>} disposition matrix over the logical task-state dictionary. Each spec method
 * covers one state's disposition per the tracker-take spec's "Explicit-mode disposition by task
 * state" scenarios.
 */
class TakeDispositionSpec extends TakeResumeSpecBase {

    // A fixed "now" for the display-only last-beat age; the seeded claim version below is 47 minutes
    // older, so the takeover facts render the age as "47m".
    private static final Instant NOW = Instant.parse('2026-07-29T12:00:00Z')

    private TakeDisposition newDisposition() {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeDisposition(newAssembly(), worktreesRoot, abortHandler, ABORT_THRESHOLD, 'taskId', [])
    }

    // The takeover-aware construction (task 6.2, FR6): a chosen confirmation seam and --takeover flag
    // over a fixed clock, so the Working case's TakeTakeover path is exercised deterministically.
    private TakeDisposition newTakeoverDisposition(TakeoverConfirmation confirmation, boolean takeoverFlag) {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeDisposition(
                newAssembly(), worktreesRoot, abortHandler, ABORT_THRESHOLD, 'taskId', [],
                ClaimBeat.NONE, takeoverFlag, confirmation, Clock.fixed(NOW, ZoneOffset.UTC), new ClaimLossFlag())
    }

    private static OpenTask workingOpenTask(String holder, Instant beatAt = NOW.minusSeconds(47 * 60)) {
        new OpenTask(REF, new TrackerTaskState.Working(holder), new ClaimVersion('claim-comment-1', beatAt), 'fixture title')
    }

    private static TrackerTask trackerTask(TrackerTaskState state, String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none(), false)
    }

    private static TrackerTask trackerTask(TrackerTaskState state, boolean finished, String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none(), finished)
    }

    // Scenario: Mandate overrides readiness and backoff — a Ready task with no prior branch is
    // claimed and worked from scratch (fresh claim path). FR8 of add-factory-serve: the explicit
    // mandate pierces the abort-backoff filter and the WIP limit for this Ready target — neither
    // is ever consulted on this path.
    def "Ready with no existing branch claims and works the task from scratch, delivering it"() {
        given:
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready()), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Delivered
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1').exitCode() == 0
    }

    // FR6 of add-git-workflow, reused here: a fresh claim also prunes stale worktree
    // registrations left behind by a directory deleted outside git (e.g. an operator's rm -rf
    // of a leftover from an earlier, unrelated task) — proven the same way
    // TaskWorktreeCleanupSpec proves pruneWorktrees itself: a stale registration for a directory
    // deleted outside git no longer appears in `git worktree list` after the claim.
    def "Ready with no existing branch prunes stale worktree registrations before claiming"() {
        given: 'a worktree directory registered then deleted outside git, leaving a stale registration'
        def staleWorktree = worktreesRoot.resolve('my-project').resolve('STALE-1')
        gitRunner.run(cloneDir, 'worktree', 'add', staleWorktree.toString(), '-b', 'stale-branch')
        staleWorktree.toFile().deleteDir()
        def before = gitRunner.run(cloneDir, 'worktree', 'list', '--porcelain')
        assert before.stdout().contains(staleWorktree.toString())

        and:
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready()), tracker, INSTANCE)

        then: 'the stale registration is gone — pruneWorktrees ran as part of the fresh claim'
        def after = gitRunner.run(cloneDir, 'worktree', 'list', '--porcelain')
        !after.stdout().contains(staleWorktree.toString())
    }

    // FR14 "Runner crash is an abort", D16 "an uncaught exception runs the abort protocol and exits
    // 12 or 13, never a bare 1": an uncaught RuntimeException of the post-claim run — here a tracker
    // write itself failing — funnels into the abort protocol rather than escaping as a bare exit 1,
    // so the abort is recorded (claim released back to Ready) and the run exits 12.
    def "an uncaught exception during the claimed run aborts and exits 12, not a bare 1"() {
        given: 'a fresh Ready claim whose terminal tracker write blows up mid-run'
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        tracker.finish(*_) >> { throw new RuntimeException('github 500 on finish') }
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready()), tracker, INSTANCE)

        then: 'the crash became a recorded abort, not an escaping exception, and maps to exit 12'
        noExceptionThrown()
        result instanceof TakeResult.Aborted
        (result as TakeResult.Aborted).cause().contains('github 500 on finish')
        1 * tracker.recordAbort(REF, _)
        TakeExitCodeMapper.exitCodeFor(result) == 12
    }

    // D16: codes shared with `run` keep their meaning — a UsageException (bad --base on a fresh
    // claim, exit 2) is deliberate control flow, not an infrastructure crash, so it propagates
    // unchanged and is never folded into the abort protocol.
    def "a UsageException during the claimed run propagates as exit 2, never converted to an abort"() {
        given: 'a fresh Ready claim with an unresolvable --base'
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        disposition.dispose(
                cloneDir, 'no-such-base-ref', pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready()), tracker, INSTANCE)

        then:
        thrown(UsageException)
        0 * tracker.recordAbort(*_)
        0 * tracker.park(*_)
    }

    // FR1 of add-claim-heartbeat: dispatchAfterClaim is the single claim-holding choke point, so
    // the beat lifecycle must bracket the claimed run — register the instant the claim is held,
    // unregister in a finally even when the run exits via deliberate control flow (the bad --base
    // UsageException path here, chosen as the cheapest post-claim exit: no engine round, no agent
    // process). Two then-blocks enforce the order. Deliberately fast and interaction-based: the
    // full lifecycle rehearsals (TakeHeartbeatLifecycleSpecBase and the death-and-recovery spec)
    // also kill PIT's dropped-register/unregister mutants, but only after multi-second bounded
    // waits that can outlive PIT's per-mutation budget under load (a flaky TIMED_OUT instead of a
    // clean kill); this spec kills both mutants in milliseconds.
    def "the beat lifecycle brackets a claimed run even when it exits via deliberate control flow"() {
        given: 'a fresh Ready claim whose post-claim work exits via a deliberate UsageException'
        def beat = Mock(ClaimBeat)
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        def disposition = new TakeDisposition(
                newAssembly(), worktreesRoot, abortHandler, ABORT_THRESHOLD, 'taskId', [],
                beat, false, TakeoverConfirmation.UNAVAILABLE, Clock.fixed(NOW, ZoneOffset.UTC),
                new ClaimLossFlag())

        when:
        disposition.dispose(
                cloneDir, 'no-such-base-ref', pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready()), tracker, INSTANCE)

        then: 'the claim is registered for beating the instant it is held'
        thrown(UsageException)
        1 * beat.register(REF)

        then: 'and unregistered on the way out, despite the deliberate exception'
        1 * beat.unregister(REF)
    }

    // D16: a DivergedBranchException on resume (local/origin divergence, exit 5) is likewise a
    // deliberate, operator-actionable outcome — it propagates unchanged rather than becoming an
    // abort.
    def "a DivergedBranchException during a resumed run propagates as exit 5, never an abort"() {
        given: 'a task branch pushed to a real origin, then diverged from a peer push'
        def bare = initBareRepo(tempDir, 'origin.git')
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        gitRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        def taskId = 'PROJ-22'
        repository().createTask(context(taskId), null)
        gitRunner.run(cloneDir, 'push', 'origin', 'gnomish/PROJ-22')
        def worktree = expectedWorktree(taskId)

        and: 'this worktree gains a local commit never pushed'
        Files.writeString(worktree.resolve('local-only.txt'), 'local work')
        gitRunner.run(worktree, 'add', 'local-only.txt')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'local work')

        and: 'a peer independently pushes a different commit for the same task branch'
        def peerClone = tempDir.resolve('peer-clone-22')
        gitRunner.run(tempDir, 'clone', bare.toString(), peerClone.toString())
        gitRunner.run(peerClone, 'fetch', 'origin', 'gnomish/PROJ-22:refs/remotes/origin/gnomish/PROJ-22')
        gitRunner.run(peerClone, 'checkout', 'gnomish/PROJ-22')
        Files.writeString(peerClone.resolve('peer-only.txt'), 'peer work')
        gitRunner.run(peerClone, 'add', 'peer-only.txt')
        gitRunner.run(peerClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'peer work')
        gitRunner.run(peerClone, 'push', 'origin', 'gnomish/PROJ-22')

        and:
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), taskId), tracker, INSTANCE)

        then:
        thrown(DivergedBranchException)
        0 * tracker.recordAbort(*_)
        0 * tracker.park(*_)
    }

    // Scenario: resuming from the branch outcome when one is recorded — Ready, but a branch
    // already exists from a prior visit (e.g. after a K-fuse park was force-flipped back to
    // Ready), resumes instead of re-creating the branch.
    def "Ready with an existing branch resumes it instead of creating a new one"() {
        given:
        def taskId = 'PROJ-2'
        repository().createTask(context(taskId), null)
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def disposition = newDisposition()
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), taskId), tracker, INSTANCE)

        then: 'the engine resumed (not a second createTask) and completed'
        result instanceof TakeResult.Delivered
    }

    // Ready + existing branch + a recorded ESCALATION-kind outcome (DecisionNeeded) with no
    // pending reply re-parks restating the question rather than blindly resuming.
    def "Ready with an existing branch and a DecisionNeeded outcome re-parks restating the question"() {
        given:
        def taskId = 'PROJ-3'
        repository().createTask(context(taskId), null)
        def afterRound = TaskState.atStageStart('build')
        persistOneRound(taskId, afterRound)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        def escalatedState = new TaskState(afterRound.position(), 1, afterRound.attempts(), afterRound.totals())
        repository().recordOutcome(taskId, new TaskOutcome.Escalated(escalatedState, report))
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        tracker.collectDecisions(REF) >> []
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), taskId), tracker, INSTANCE)

        then:
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION
        (result as TakeResult.AwaitingHuman).report().contains('continue?')
        1 * tracker.park(REF, ParkReason.ESCALATION, { it.contains('continue?') })
    }

    // Ready + existing branch + a recorded Completed outcome whose tracker finish never landed (a
    // dead instance or a dead tracker at the finish line, FR10/D10/NFR-C1 of add-claim-heartbeat).
    // The Completed cleanup commit (FR15) removed .gnomish-task/ from the tip, so bootstrap finds
    // no live task.json — but the delivered task.json + state.json survive at the cleanup commit's
    // parent. Reconcile-on-resume recovers them and posts the DEFERRED finish, exiting Delivered
    // with ZERO engine rounds (M4), replacing the former refusal: the branch already carries the
    // delivered outcome, so re-running paid work — or demanding a human reconcile labels — is wrong.
    // Zero rounds is proven by the task branch tip commit being unchanged across the run (an engine
    // round would add commits); the deferred finish is proven to come from the branch-recorded
    // delivery (not a fresh render) by naming the task and its branch.
    def "Ready with an existing branch recorded Completed reconciles the deferred finish, zero engine rounds"() {
        given: 'a delivered branch whose finish never reached the tracker'
        def taskId = 'PROJ-4'
        repository().createTask(context(taskId), null)
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        repository().recordOutcome(taskId, new TaskOutcome.Completed(state))
        def tipBefore = gitRunner.run(cloneDir, 'rev-parse', 'gnomish/PROJ-4').stdout().strip()
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), taskId), tracker, INSTANCE)

        then: 'the deferred finish was posted from the branch-recorded delivery, and the run delivered'
        result instanceof TakeResult.Delivered
        1 * tracker.finish(REF, { it.contains('PROJ-4') && it.contains('Branch: gnomish/PROJ-4') })

        and: 'no engine round ran — the task branch tip is unchanged (M4: zero rounds, no new commits)'
        gitRunner.run(cloneDir, 'rev-parse', 'gnomish/PROJ-4').stdout().strip() == tipBefore
    }

    // Ready + existing branch + a recorded Aborted outcome: the abort protocol (FR14) returns a
    // below-K abort to Ready precisely so a later claim retries it. The take mandate resumes on
    // the return alone from the last durable state.json position rather than refusing — "any other
    // recorded outcome continues on the return alone" (FR9, D12). Contrast manual-run
    // GitResumeRunner, which refuses; refusing here would strand every below-K abort forever.
    def "Ready with an existing branch recorded Aborted resumes it on the return alone"() {
        given:
        def taskId = 'PROJ-5'
        repository().createTask(context(taskId), null)
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        repository().recordOutcome(
                taskId, new TaskOutcome.Aborted(state, new AttemptKey(taskId, 'build', 0), 'disk full'))
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), taskId), tracker, INSTANCE)

        then: 'the engine resumed (no decision dialog) and completed'
        result instanceof TakeResult.Delivered
    }

    // Scenario: Held task is refused — claim race lost between fetch and claim.
    def "Ready but claim race lost refuses naming the winning instance, no branch created"() {
        given:
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-x1y2z3')
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready()), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().contains('gnomish-other-x1y2z3')
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1').exitCode() != 0
    }

    // Scenario "Held task shows facts and asks" (FR6, D9): a Working task with a TTY attached prints
    // the holder and last-beat age and asks; declining leaves the tracker untouched and exits as a
    // refusal naming the holder. The confirmation seam is the "prints facts + asks" surface — here a
    // Mock verifying it was asked with the holder and the computed age (47m), answering DECLINED.
    def "Working with a TTY shows holder and last-beat age, declining refuses untouched"() {
        given:
        def confirmation = Mock(TakeoverConfirmation)
        openFronts = [
            workingOpenTask('gnomish-other-x1y2z3')
        ]
        def disposition = newTakeoverDisposition(confirmation, false)

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-other-x1y2z3')), tracker, INSTANCE)

        then: 'the operator was asked with the holder and the display-only last-beat age'
        1 * confirmation.confirm(REF, 'gnomish-other-x1y2z3', '47m') >> TakeoverConfirmation.Decision.DECLINED

        and: 'declining refuses naming the holder and changes nothing in the tracker'
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().contains('gnomish-other-x1y2z3')
        0 * tracker.removeStaleClaim(*_)
        0 * tracker.claim(*_)
        0 * tracker.park(*_)
        0 * tracker.finish(*_)
    }

    // FR6, D9: the display-only last-beat age shown in the takeover facts, computed as
    // Duration.between(claim version updatedAt, now) on the run's clock and rendered compactly. A
    // future/absent version clamps to 0s / "unknown" respectively; the seam (Mock) captures the
    // rendered age, and DECLINED short-circuits so no other tracker call runs.
    def "takeover facts render the last-beat age as '#expectedAge'"() {
        given:
        def confirmation = Mock(TakeoverConfirmation)
        openFronts = openTasks
        def disposition = newTakeoverDisposition(confirmation, false)

        when:
        disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-dead-x1')), tracker, INSTANCE)

        then:
        1 * confirmation.confirm(REF, 'gnomish-dead-x1', expectedAge) >> TakeoverConfirmation.Decision.DECLINED

        where:
        expectedAge | openTasks
        '0s'        | [
            workingOpenTask('gnomish-dead-x1', NOW.plusSeconds(60))
        ]
        '30s'       | [
            workingOpenTask('gnomish-dead-x1', NOW.minusSeconds(30))
        ]
        '1m'        | [
            workingOpenTask('gnomish-dead-x1', NOW.minusSeconds(60))
        ]
        '5m'        | [
            workingOpenTask('gnomish-dead-x1', NOW.minusSeconds(300))
        ]
        '1h 0m'     | [
            workingOpenTask('gnomish-dead-x1', NOW.minusSeconds(3600))
        ]
        '2h 5m'     | [
            workingOpenTask('gnomish-dead-x1', NOW.minusSeconds(2 * 3600 + 5 * 60))
        ]
        'unknown'   | []
    }

    // Scenario "Headless takeover needs the flag" (FR6): no TTY (confirmation UNAVAILABLE) and no
    // --takeover flag → refuse, naming the holder AND mentioning the flag; nothing is mutated.
    def "Working headless without the flag refuses naming the holder and the --takeover flag"() {
        given:
        openFronts = [
            workingOpenTask('gnomish-other-x1y2z3')
        ]
        def disposition = newTakeoverDisposition(TakeoverConfirmation.UNAVAILABLE, false)

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-other-x1y2z3')), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason()
        reason.contains('gnomish-other-x1y2z3')
        reason.contains('--takeover')
        0 * tracker.removeStaleClaim(*_)
        0 * tracker.claim(*_)
        0 * tracker.park(*_)
        0 * tracker.finish(*_)
    }

    // Scenario "Confirmed takeover resumes the task" (FR6, D9): the operator confirms → the old claim
    // is removed via removeStaleClaim, the run claims by the ordinary lease and resumes from the
    // existing branch. Uses a real branch (like the Ready-resume test) so the confirmed path is the
    // SAME claimAndWork/resume the Ready case runs.
    def "Working confirmed via TTY removes the stale claim, claims ordinarily, and resumes to Delivered"() {
        given: 'an existing branch for the held task, resumable from its last durable round'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def observed = new ClaimVersion('claim-comment-1', NOW.minusSeconds(47 * 60))
        openFronts = [
            new OpenTask(REF, new TrackerTaskState.Working('gnomish-dead-x1'), observed, 'fixture title')
        ]
        def confirmation = { r, h, a -> TakeoverConfirmation.Decision.CONFIRMED } as TakeoverConfirmation
        def disposition = newTakeoverDisposition(confirmation, false)

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-dead-x1'), taskId), tracker, INSTANCE)

        then: 'the old claim was removed with the observed version, then the ordinary lease claimed it'
        1 * tracker.removeStaleClaim(REF, observed) >> new RemoveStaleClaimResult.Removed()
        1 * tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()

        and: 'the run resumed from the branch and delivered'
        result instanceof TakeResult.Delivered
    }

    // Scenario "Headless takeover needs the flag" — with the flag (FR6): no TTY, but --takeover
    // authorizes it, so it proceeds exactly as a confirmed takeover (removeStaleClaim + claim +
    // resume) without ever consulting the confirmation seam.
    def "Working headless with --takeover proceeds as a confirmed takeover, bypassing the seam"() {
        given:
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null)
        persistOneRound(taskId, TaskState.atStageStart('build'))
        def observed = new ClaimVersion('claim-comment-1', NOW.minusSeconds(47 * 60))
        openFronts = [
            new OpenTask(REF, new TrackerTaskState.Working('gnomish-dead-x1'), observed, 'fixture title')
        ]
        def confirmation = Mock(TakeoverConfirmation)
        def disposition = newTakeoverDisposition(confirmation, true)

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-dead-x1'), taskId), tracker, INSTANCE)

        then: 'the seam is never asked — the flag is the headless authorization'
        0 * confirmation.confirm(*_)

        and: 'it still removes the stale claim, claims ordinarily, and resumes to Delivered'
        1 * tracker.removeStaleClaim(REF, observed) >> new RemoveStaleClaimResult.Removed()
        1 * tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        result instanceof TakeResult.Delivered
    }

    // Edge: the operator confirms but no live claim version is observable (the ref is absent from
    // listOpen, or its claim marker is gone) — there is nothing to remove, so removeStaleClaim is
    // skipped and the ordinary claim decides: still Working → Held → refuse naming the holder.
    def "Working confirmed with no observable claim version skips removeStaleClaim and refuses on Held"() {
        given:
        openFronts = []
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held('gnomish-dead-x1')
        def disposition = newTakeoverDisposition(TakeoverConfirmation.UNAVAILABLE, true)

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-dead-x1')), tracker, INSTANCE)

        then:
        0 * tracker.removeStaleClaim(*_)
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().contains('gnomish-dead-x1')
    }

    // A confirmed takeover that loses the race: removeStaleClaim reports Mismatch (the holder beat
    // between listOpen and removal), so the task is still Working and the ordinary claim comes back
    // Held — the run refuses naming the current holder, tracker converges (NFR-R2).
    def "Working confirmed but the takeover loses the race refuses naming the current holder"() {
        given:
        def observed = new ClaimVersion('claim-comment-1', NOW.minusSeconds(47 * 60))
        openFronts = [
            new OpenTask(REF, new TrackerTaskState.Working('gnomish-dead-x1'), observed, 'fixture title')
        ]
        tracker.removeStaleClaim(REF, observed) >> new RemoveStaleClaimResult.Mismatch(observed)
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Held('gnomish-live-x2')
        def confirmation = { r, h, a -> TakeoverConfirmation.Decision.CONFIRMED } as TakeoverConfirmation
        def disposition = newTakeoverDisposition(confirmation, false)

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-dead-x1')), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().contains('gnomish-live-x2')
    }

    // Scenario: Parked task is refused — every ParkReason names the reason and a return path,
    // without inventing report content (the port has no "read current report" op).
    def "AwaitingHuman(ESCALATION) refuses naming the reason and the reply-and-return path"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason()
        reason.contains('ESCALATION')
        reason.toLowerCase().contains('reply')
        reason.toLowerCase().contains('ready')
        0 * tracker.claim(*_)
    }

    def "AwaitingHuman(CHECKPOINT) refuses naming the reason and the return-to-ready path"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT)), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason()
        reason.contains('CHECKPOINT')
        reason.toLowerCase().contains('ready')
        0 * tracker.claim(*_)
    }

    def "AwaitingHuman(INFRA) refuses naming the reason and the fix-and-return path"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.AwaitingHuman(ParkReason.INFRA)), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason()
        reason.contains('INFRA')
        reason.toLowerCase().contains('fix')
        0 * tracker.claim(*_)
    }

    // Scenario: Finished task is skipped — reports "already done", no resume, no tracker mutation.
    def "Finished is skipped as already done, no tracker mutation"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Finished()), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().toLowerCase().contains('already done')
        0 * tracker.claim(*_)
        0 * tracker.finish(*_)
    }

    // Scenario: Ready but finished (a human reopened a previously-finished task) refuses the
    // mandate rather than claiming it — the decline protocol runs instead (FR5 of
    // enforce-finish-terminality).
    def "Ready but finished refuses the mandate, declines, and does not claim"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), true), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().toLowerCase().contains('already finished')
        1 * tracker.declineFinished(REF, _)
        0 * tracker.claim(*_)
    }

    // Asymmetry with the best-effort feed sweep (FinishedDecline swallows and logs): the explicit
    // take mandate declines LOUDLY — a declineFinished failure on this path is not caught, it
    // propagates so the operator sees a hard failure of the mandate rather than a silent skip.
    def "Ready but finished propagates a declineFinished failure instead of swallowing it"() {
        given:
        def disposition = newDisposition()
        tracker.declineFinished(REF, _) >> { throw new RuntimeException('tracker down') }

        when:
        disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), true), tracker, INSTANCE)

        then:
        thrown(RuntimeException)
        0 * tracker.claim(*_)
    }

    // Gone (closed or nonexistent) is skipped with a clear error, no tracker mutation.
    def "Gone is skipped with a clear closed-or-nonexistent error, no tracker mutation"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Gone()), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason().toLowerCase()
        reason.contains('closed') || reason.contains('exist')
        0 * tracker.claim(*_)
    }
}
