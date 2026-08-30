package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.DivergedBranchException
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files

/**
 * FR8, FR9, NFR-R3 of add-git-workflow (task 4.6): resume bootstrap — locate the branch,
 * materialize the worktree, reconcile local/origin divergence, and load and version-gate
 * {@code task.json} back into the handoff bundle.
 */
class GitResumeBootstrapSpec extends GitResumeSpecBase {

    // FR8: a locally-present branch resumes without any fetch; the worktree is materialized and
    // task.json is loaded back into the handoff bundle.
    def "bootstrap() finds a local branch, materializes the worktree, and loads task.json"() {
        given: 'a task started (but not completed) via the git task repository'
        repository().createTask(context('PROJ-1'), null, TaskState.atStageStart('implement'))

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-1')

        then:
        bundle.taskId() == 'PROJ-1'
        bundle.context() == context('PROJ-1')
        bundle.outcome() == null
        bundle.lastEscalation() == null
        bundle.branchName() == 'gnomish/PROJ-1'
        bundle.baseCommit() != null
        bundle.worktreePath() == expectedWorktree('PROJ-1')
        Files.isDirectory(bundle.worktreePath())
        Files.exists(bundle.worktreePath().resolve('.gnomish-task').resolve('task.json'))
    }

    // FR8: a task escalated in a prior visit round-trips its outcome and lastEscalation through
    // the bundle.
    def "bootstrap() surfaces a recorded outcome and lastEscalation"() {
        given:
        def repo = repository()
        repo.createTask(context('PROJ-2'), null, TaskState.atStageStart('implement'))
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repo.recordOutcome('PROJ-2', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-2')

        then:
        bundle.outcome() != null
        bundle.outcome() instanceof RecordedOutcome.Escalated
        bundle.lastEscalation() == report
    }

    // FR8: resuming on a machine/clone without a local worktree still succeeds, materializing one
    // fresh from the branch.
    def "bootstrap() materializes a fresh worktree when none exists locally yet"() {
        given:
        repository().createTask(context('PROJ-3'), null, TaskState.atStageStart('implement'))
        def worktree = expectedWorktree('PROJ-3')
        gitOutput(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        assert !Files.exists(worktree)

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-3')

        then:
        Files.isDirectory(bundle.worktreePath())
        bundle.taskId() == 'PROJ-3'
    }

    // FR8: a taskId with no branch anywhere (local, remote-tracking, or on origin) is a clear
    // operator-facing usage error, not a stack trace.
    def "bootstrap() throws UsageException naming the taskId when the branch is not found anywhere"() {
        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-MISSING')

        then:
        def ex = thrown(UsageException)
        ex.message.contains('PROJ-MISSING')
    }

    // FR4: an unsupported task.json version is a clear refusal naming the file and version, not a
    // stack trace surfaced past this bootstrap step.
    def "bootstrap() throws UnsupportedStateFileVersionException naming task.json and the found version"() {
        given:
        repository().createTask(context('PROJ-4'), null, TaskState.atStageStart('implement'))
        def taskJson = expectedWorktree('PROJ-4').resolve('.gnomish-task').resolve('task.json')
        def rewritten = Files.readString(taskJson).replaceFirst(/"version"\s*:\s*1/, '"version":2')
        gitOutput(expectedWorktree('PROJ-4'), 'worktree', 'remove', '--force', expectedWorktree('PROJ-4').toString())

        and: 'the branch tip is rewritten to carry an unsupported task.json version'
        def rewriteWorktree = tempDir.resolve('rewrite-scratch')
        gitOutput(cloneDir, 'worktree', 'add', rewriteWorktree.toString(), 'gnomish/PROJ-4')
        Files.writeString(rewriteWorktree.resolve('.gnomish-task').resolve('task.json'), rewritten)
        commitAll(rewriteWorktree, 'bump version')
        gitOutput(cloneDir, 'worktree', 'remove', '--force', rewriteWorktree.toString())

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-4')

        then:
        def ex = thrown(UnsupportedStateFileVersionException)
        ex.fileName() == 'task.json'
        ex.foundVersion() == 2
    }

    // FR9, NFR-R3: bootstrap() reconciles local/origin divergence before task.json is read back —
    // local behind origin fast-forwards the worktree, discarding uncommitted leftovers, so a
    // peer instance's already-pushed work is picked up on resume rather than silently ignored.
    def "bootstrap() fast-forwards a worktree that is behind origin and discards uncommitted leftovers"() {
        given: 'a task branch pushed to a real origin'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-20'), null, TaskState.atStageStart('implement'))
        gitOutput(cloneDir, 'push', 'origin', 'gnomish/PROJ-20')
        def worktree = expectedWorktree('PROJ-20')

        and: 'another instance clones and pushes a further commit for the same task to origin'
        def peerClone = tempDir.resolve('peer-clone')
        gitOutput(tempDir, 'clone', bare.toString(), peerClone.toString())
        gitOutput(peerClone, 'fetch', 'origin', 'gnomish/PROJ-20:refs/remotes/origin/gnomish/PROJ-20')
        gitOutput(peerClone, 'checkout', 'gnomish/PROJ-20')
        Files.writeString(peerClone.resolve('peer-work.txt'), 'peer commit')
        commitAll(peerClone, 'peer work')
        gitOutput(peerClone, 'push', 'origin', 'gnomish/PROJ-20')

        and: 'this worktree still has uncommitted leftovers from before it died'
        Files.writeString(worktree.resolve('leftover.txt'), 'stale')

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-20')

        then: 'the worktree fast-forwarded to the peer\'s pushed commit'
        Files.exists(bundle.worktreePath().resolve('peer-work.txt'))

        and: 'the uncommitted leftover was discarded'
        !Files.exists(bundle.worktreePath().resolve('leftover.txt'))
    }

    // FR9, NFR-R3: local unpushed commits (this instance is ahead of origin) resume from local
    // as-is — a later best-effort push (FR11) catches origin up, no data is discarded.
    def "bootstrap() leaves a worktree ahead of origin untouched"() {
        given: 'a task branch pushed to a real origin, then an unpushed local commit'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-21'), null, TaskState.atStageStart('implement'))
        gitOutput(cloneDir, 'push', 'origin', 'gnomish/PROJ-21')
        def worktree = expectedWorktree('PROJ-21')
        Files.writeString(worktree.resolve('unpushed.txt'), 'local only')
        commitAll(worktree, 'local work')
        def localTipBefore = gitOutput(worktree, 'rev-parse', 'HEAD')

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-21')

        then: 'the worktree is untouched — still at the local, unpushed tip'
        gitOutput(bundle.worktreePath(), 'rev-parse', 'HEAD') == localTipBefore
        Files.exists(bundle.worktreePath().resolve('unpushed.txt'))
    }

    // FR8, NFR-R3 of harden-task-branch-contract: diverged local/origin histories no longer stop
    // resume for a human — arbitration became decidable once the claim protocol landed, so origin
    // wins and the unpushed local line, which was never durable for the fleet, is discarded. The
    // arbitration is the claim protocol's, so the discard runs only where a tenure is held; manual
    // run --resume, which claims nothing, keeps the pre-FR8 stop-and-report (see the spec below).
    def "FR8: bootstrap() under a tenure discards a diverged local line and resumes from origin"() {
        given: 'a task branch pushed to a real origin'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-22'), null, TaskState.atStageStart('implement'))
        gitOutput(cloneDir, 'push', 'origin', 'gnomish/PROJ-22')
        def worktree = expectedWorktree('PROJ-22')

        and: 'this worktree gains a local commit never pushed'
        Files.writeString(worktree.resolve('local-only.txt'), 'local work')
        commitAll(worktree, 'local work')
        def localTipBefore = gitOutput(worktree, 'rev-parse', 'HEAD')

        and: 'a peer instance independently pushes a different commit for the same task'
        def peerClone = tempDir.resolve('peer-clone-22')
        gitOutput(tempDir, 'clone', bare.toString(), peerClone.toString())
        gitOutput(peerClone, 'fetch', 'origin', 'gnomish/PROJ-22:refs/remotes/origin/gnomish/PROJ-22')
        gitOutput(peerClone, 'checkout', 'gnomish/PROJ-22')
        Files.writeString(peerClone.resolve('peer-only.txt'), 'peer work')
        commitAll(peerClone, 'peer work')
        gitOutput(peerClone, 'push', 'origin', 'gnomish/PROJ-22')

        and: 'what origin ends up holding is the peer line'
        def originTip = gitOutput(bare, 'rev-parse', 'refs/heads/gnomish/PROJ-22')

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out, TaskGitFixture.real(tenureOn('PROJ-22')))
                .bootstrap(cloneDir, 'PROJ-22')

        then: 'resume continues from origin, with no exception demanding git surgery'
        noExceptionThrown()
        gitOutput(worktree, 'rev-parse', 'HEAD') == originTip
        gitOutput(worktree, 'rev-parse', 'HEAD') != localTipBefore

        and: 'the unpushed local commit is gone and the peer work is present'
        !Files.exists(worktree.resolve('local-only.txt'))
        Files.exists(worktree.resolve('peer-only.txt'))
    }

    // FR8: the discard is justified by the claim protocol — origin advances only through
    // legitimate lease holders — and `gnomish run --resume` runs that protocol not at all. There
    // the local line may be the operator's only copy and nothing arbitrated it against origin, so
    // the bootstrap fails closed with the pre-FR8 stop-and-report instead of destroying it.
    def "FR8: bootstrap() with no claim on the task refuses a diverged branch instead of discarding it"() {
        given: 'a task branch pushed to a real origin'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-23'), null, TaskState.atStageStart('implement'))
        gitOutput(cloneDir, 'push', 'origin', 'gnomish/PROJ-23')
        def worktree = expectedWorktree('PROJ-23')

        and: 'this worktree gains a local commit never pushed'
        Files.writeString(worktree.resolve('local-only.txt'), 'local work')
        commitAll(worktree, 'local work')
        def localTipBefore = gitOutput(worktree, 'rev-parse', 'HEAD')

        and: 'someone else independently pushes a different commit for the same task'
        def peerClone = tempDir.resolve('peer-clone-23')
        gitOutput(tempDir, 'clone', bare.toString(), peerClone.toString())
        gitOutput(peerClone, 'fetch', 'origin', 'gnomish/PROJ-23:refs/remotes/origin/gnomish/PROJ-23')
        gitOutput(peerClone, 'checkout', 'gnomish/PROJ-23')
        Files.writeString(peerClone.resolve('peer-only.txt'), 'peer work')
        commitAll(peerClone, 'peer work')
        gitOutput(peerClone, 'push', 'origin', 'gnomish/PROJ-23')

        when: 'the claimless manual-run bootstrap reaches the reconciliation'
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-23')

        then: 'the operator is handed the decision, not a repaired branch'
        def ex = thrown(DivergedBranchException)
        ex.message.contains('PROJ-23')
        ex.message.contains('holds no claim')

        and: 'the local line is intact — nothing was reset, nothing was cleaned'
        gitOutput(worktree, 'rev-parse', 'HEAD') == localTipBefore
        Files.exists(worktree.resolve('local-only.txt'))
        !Files.exists(worktree.resolve('peer-only.txt'))
    }

    /** A tenure held on {@code taskId} and nothing else — the take path's shape. */
    private static ClaimEpochSource tenureOn(String taskId) {
        { String asked ->
            asked == taskId ? Optional.of(new ClaimEpoch(11L)) : Optional.empty()
        } as ClaimEpochSource
    }
}
