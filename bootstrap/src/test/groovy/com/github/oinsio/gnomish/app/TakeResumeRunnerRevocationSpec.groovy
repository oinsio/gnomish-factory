package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files

/**
 * FR15, D2 of add-tracker-port (task 5.6): when the wrapped {@code AttemptPersistence} detects
 * mid-run revocation (another instance or a human took the task over), it is recorded on the
 * decorator instance rather than reaching the caller as a thrown exception — {@code
 * AttemptJournal#commit} inside the engine catches the throw and turns it into a {@code
 * TaskOutcome.Aborted}, per {@code AttemptPersistence}'s own documented contract. The take resume
 * tail queries {@code RevocationCheckingAttemptPersistence#revocation()} immediately after {@code
 * engine.run(...)} returns and, when present, delegates to {@code RevocationHandler} instead of
 * treating the {@code Aborted} outcome as a real infrastructure abort — {@code
 * GitOutcomeRecorder#recordAndCleanUp} must NOT run on this path (FR15: tracker state and cleanup
 * are left exactly as revocation found them).
 */
class TakeResumeRunnerRevocationSpec extends TakeResumeSpecBase {

    // FR15: the tracker no longer reports this instance as the working holder after the first
    // round persists — the round-boundary check in RevocationCheckingAttemptPersistence throws
    // (caught inside the engine and turned into Aborted), the take tail sees revocation() is
    // present on the decorator and routes to RevocationHandler instead of AbortHandler, and the
    // result is Revoked.
    def "resumeWithoutDecision returns Revoked when the tracker reports the claim lost mid-run, and skips outcome recording"() {
        given: 'a single-stage AUTO pipeline — one round completes the whole task in one persist'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null)
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def runner = newTakeResumeRunner()
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        and: 'the tracker reports the claim held by a different instance once the round persists'
        workingHolder = 'someone-else-xyz123'

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then: 'the result is Revoked'
        result instanceof TakeResult.Revoked

        and: 'the tracker state-changing calls are the revocation protocol only — never park/recordAbort/finish'
        0 * tracker.park(*_)
        0 * tracker.recordAbort(*_)
        0 * tracker.finish(*_)
        1 * tracker.postNote(REF, _)
        1 * tracker.release(REF)

        and: 'no new Completed/outcome commit landed on the branch beyond the round already persisted before revocation — the revocation salvage protocol only commits leftovers, never GitOutcomeRecorder'
        // Either unchanged (nothing to salvage) or advanced only by a salvage commit — never by a
        // GitTaskRepository#recordOutcome commit, which would also strip .gnomish-task/ on Completed.
        Files.exists(bootstrap.worktreePath().resolve('.gnomish-task').resolve('task.json'))
    }

    // FR8, D7 of add-claim-heartbeat: a beat that already proved the claim gone sets the
    // ClaimLossFlag; at the next round boundary the run reacts EXACTLY as to a revocation
    // (salvage/push/release, never park/finish/recordAbort) — even though the tracker's own
    // fetchTask still reports the claim as ours (workingHolder stays this instance). This proves the
    // flag is live end to end, not write-only.
    def "resumeWithoutDecision reacts to a set claim-loss flag as a revocation, even while fetchTask still reports the claim ours"() {
        given: 'a single-stage AUTO pipeline and a flag the beat has already set for this task'
        def taskId = 'PROJ-1'
        repository().createTask(context(taskId), null)
        def state = TaskState.atStageStart('build')
        persistOneRound(taskId, state)
        def flag = new ClaimLossFlag()
        flag.claimLost(REF)
        def runner = newTakeResumeRunner(
                new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')), testProperties(), [], flag)
        def bootstrap = runner.bootstrap(cloneDir, taskId)

        and: 'the tracker itself still reports the claim held by THIS instance (only the flag says lost)'
        assert workingHolder == INSTANCE.value()

        when:
        def result = runner.resumeWithoutDecision(
                cloneDir, bootstrap, pipeline(), state, RunArguments.InteractiveMode.ALL, false, tracker, REF, INSTANCE)

        then: 'the run reacts as a revocation'
        result instanceof TakeResult.Revoked

        and: 'the revocation protocol runs — never park/recordAbort/finish'
        0 * tracker.park(*_)
        0 * tracker.recordAbort(*_)
        0 * tracker.finish(*_)
        1 * tracker.postNote(REF, _)
        1 * tracker.release(REF)
    }
}
