package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
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

/**
 * FR9, UX2 of add-tracker-port (task 5.9): {@link TakeDisposition#dispose} — the explicit-mode
 * {@code take <ref>} disposition matrix over the logical task-state dictionary. Each spec method
 * covers one state's disposition per the tracker-take spec's "Explicit-mode disposition by task
 * state" scenarios.
 */
class TakeDispositionSpec extends TakeResumeSpecBase {

    private TakeDisposition newDisposition() {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeDisposition(newAssembly(), worktreesRoot, abortHandler, ABORT_THRESHOLD, 'taskId', [])
    }

    private static TrackerTask trackerTask(TrackerTaskState state, String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none())
    }

    // Scenario: Mandate overrides readiness and backoff — a Ready task with no prior branch is
    // claimed and worked from scratch (fresh claim path).
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

    // Ready + existing branch + a recorded Completed outcome (rare inconsistency: branch says
    // done but tracker still reported Ready). The branch's own cleanup commit (FR15) already
    // removed .gnomish-task/ from the tip entirely, so there is no live finalState left to reuse
    // at the tip — refuses with a UsageException naming the inconsistency rather than fabricating
    // a Delivered result with an invented TaskState.
    def "Ready with an existing branch recorded Completed refuses naming the inconsistency"() {
        given:
        def taskId = 'PROJ-4'
        repository().createTask(context(taskId), null)
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        repository().recordOutcome(taskId, new TaskOutcome.Completed(state))
        tracker.claim(REF, INSTANCE.value()) >> new ClaimResult.Acquired()
        def disposition = newDisposition()

        when:
        disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Ready(), taskId), tracker, INSTANCE)

        then:
        thrown(UsageException)
        0 * tracker.finish(*_)
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

    // Scenario: Held task is refused — already-Working, no claim attempt at all.
    def "Working held by another instance refuses naming the holder, no tracker mutation"() {
        given:
        def disposition = newDisposition()

        when:
        def result = disposition.dispose(
                cloneDir, null, pipeline(), RunArguments.InteractiveMode.ALL, false,
                trackerTask(new TrackerTaskState.Working('gnomish-other-x1y2z3')), tracker, INSTANCE)

        then:
        result instanceof TakeResult.Skipped
        (result as TakeResult.Skipped).reason().contains('gnomish-other-x1y2z3')
        0 * tracker.claim(*_)
        0 * tracker.park(*_)
        0 * tracker.finish(*_)
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
