package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.DivergedBranchException
import com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException
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
        repository().createTask(context('PROJ-1'), null)

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
        repo.createTask(context('PROJ-2'), null)
        def report = new EscalationReport.DecisionNeeded('continue?', ['yes', 'no'])
        repo.recordOutcome('PROJ-2', new TaskOutcome.Escalated(TaskState.atStageStart('implement'), report))

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-2')

        then:
        bundle.outcome() != null
        bundle.outcome().type() == 'escalated'
        bundle.lastEscalation() == report
    }

    // FR8: resuming on a machine/clone without a local worktree still succeeds, materializing one
    // fresh from the branch.
    def "bootstrap() materializes a fresh worktree when none exists locally yet"() {
        given:
        repository().createTask(context('PROJ-3'), null)
        def worktree = expectedWorktree('PROJ-3')
        gitRunner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
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
        repository().createTask(context('PROJ-4'), null)
        def taskJson = expectedWorktree('PROJ-4').resolve('.gnomish-task').resolve('task.json')
        def rewritten = Files.readString(taskJson).replaceFirst(/"version"\s*:\s*1/, '"version":2')
        gitRunner.run(expectedWorktree('PROJ-4'), 'worktree', 'remove', '--force', expectedWorktree('PROJ-4').toString())

        and: 'the branch tip is rewritten to carry an unsupported task.json version'
        def rewriteWorktree = tempDir.resolve('rewrite-scratch')
        gitRunner.run(cloneDir, 'worktree', 'add', rewriteWorktree.toString(), 'gnomish/PROJ-4')
        Files.writeString(rewriteWorktree.resolve('.gnomish-task').resolve('task.json'), rewritten)
        gitRunner.run(rewriteWorktree, 'add', '-A')
        gitRunner.run(rewriteWorktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'bump version')
        gitRunner.run(cloneDir, 'worktree', 'remove', '--force', rewriteWorktree.toString())

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
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        gitRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-20'), null)
        gitRunner.run(cloneDir, 'push', 'origin', 'gnomish/PROJ-20')
        def worktree = expectedWorktree('PROJ-20')

        and: 'another instance clones and pushes a further commit for the same task to origin'
        def peerClone = tempDir.resolve('peer-clone')
        gitRunner.run(tempDir, 'clone', bare.toString(), peerClone.toString())
        gitRunner.run(peerClone, 'fetch', 'origin', 'gnomish/PROJ-20:refs/remotes/origin/gnomish/PROJ-20')
        gitRunner.run(peerClone, 'checkout', 'gnomish/PROJ-20')
        Files.writeString(peerClone.resolve('peer-work.txt'), 'peer commit')
        gitRunner.run(peerClone, 'add', 'peer-work.txt')
        gitRunner.run(peerClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'peer work')
        gitRunner.run(peerClone, 'push', 'origin', 'gnomish/PROJ-20')

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
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        gitRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-21'), null)
        gitRunner.run(cloneDir, 'push', 'origin', 'gnomish/PROJ-21')
        def worktree = expectedWorktree('PROJ-21')
        Files.writeString(worktree.resolve('unpushed.txt'), 'local only')
        gitRunner.run(worktree, 'add', 'unpushed.txt')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'local work')
        def localTipBefore = gitRunner.run(worktree, 'rev-parse', 'HEAD').stdout().trim()

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-21')

        then: 'the worktree is untouched — still at the local, unpushed tip'
        gitRunner.run(bundle.worktreePath(), 'rev-parse', 'HEAD').stdout().trim() == localTipBefore
        Files.exists(bundle.worktreePath().resolve('unpushed.txt'))
    }

    // FR9, NFR-R3: diverged local/origin histories stop resume with a clear, operator-facing
    // error rather than guessing — auto-resolving two workers is the claim protocol's job.
    def "bootstrap() throws DivergedBranchException when local and origin have diverged, without mutating the worktree"() {
        given: 'a task branch pushed to a real origin'
        def bare = initBareRepo(tempDir, 'origin.git')
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        gitRunner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-22'), null)
        gitRunner.run(cloneDir, 'push', 'origin', 'gnomish/PROJ-22')
        def worktree = expectedWorktree('PROJ-22')

        and: 'this worktree gains a local commit never pushed'
        Files.writeString(worktree.resolve('local-only.txt'), 'local work')
        gitRunner.run(worktree, 'add', 'local-only.txt')
        gitRunner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'local work')
        def localTipBefore = gitRunner.run(worktree, 'rev-parse', 'HEAD').stdout().trim()

        and: 'a peer instance independently pushes a different commit for the same task'
        def peerClone = tempDir.resolve('peer-clone-22')
        gitRunner.run(tempDir, 'clone', bare.toString(), peerClone.toString())
        gitRunner.run(peerClone, 'fetch', 'origin', 'gnomish/PROJ-22:refs/remotes/origin/gnomish/PROJ-22')
        gitRunner.run(peerClone, 'checkout', 'gnomish/PROJ-22')
        Files.writeString(peerClone.resolve('peer-only.txt'), 'peer work')
        gitRunner.run(peerClone, 'add', 'peer-only.txt')
        gitRunner.run(peerClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'peer work')
        gitRunner.run(peerClone, 'push', 'origin', 'gnomish/PROJ-22')

        when:
        newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out).bootstrap(cloneDir, 'PROJ-22')

        then:
        def ex = thrown(DivergedBranchException)
        ex.message.contains('PROJ-22')

        and: 'the worktree was not mutated'
        gitRunner.run(worktree, 'rev-parse', 'HEAD').stdout().trim() == localTipBefore
        Files.exists(worktree.resolve('local-only.txt'))
    }
}
