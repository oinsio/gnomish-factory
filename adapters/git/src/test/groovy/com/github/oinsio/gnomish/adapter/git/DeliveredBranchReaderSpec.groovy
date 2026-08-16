package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of add-claim-heartbeat: {@link DeliveredBranchReader} recovers a delivered task's
 * pre-cleanup {@code task.json}/{@code state.json} from the commit preceding its {@code Completed}
 * cleanup commit (FR15 of add-git-workflow), so reconcile-on-resume can post the deferred finish
 * from the branch's own recorded delivery.
 */
class DeliveredBranchReaderSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    Path worktreesRoot
    GitTaskRepository repository
    DeliveredBranchReader reader

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
        repository = new GitTaskRepository(runner, cloneDir, worktreesRoot)
        reader = new DeliveredBranchReader(runner)
    }

    private static TaskContext context(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'Fix the thing', 'Body text', [])
    }

    /** Persists one real round via GitAttemptPersistence so state.json exists, as a live task would. */
    private void persistOneRound(String taskId, TaskState state) {
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def persistence = new GitAttemptPersistence(runner, worktree, taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist(taskId, state, trace)
    }

    // FR10: a Completed branch's cleanup commit strips .gnomish-task/ from the tip, but the reader
    // recovers the delivered context + finalState from the cleanup commit's parent.
    def "reads the delivered context and final state from the pre-cleanup commit"() {
        given: 'a branch delivered via recordOutcome(Completed), whose tip no longer carries the files'
        repository.createTask(context('PROJ-1'), null)
        def finalState = TaskState.atStageStart('implement')
        persistOneRound('PROJ-1', finalState)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(finalState))
        assert runner.run(cloneDir, 'show', 'gnomish/PROJ-1:.gnomish-task/task.json').exitCode() != 0

        when:
        def delivered = reader.read(cloneDir, 'PROJ-1')

        then: 'the delivered identity and final state are recovered from history'
        delivered.context().taskId() == 'PROJ-1'
        delivered.context().title() == 'Fix the thing'
        delivered.finalState() == finalState
    }

    // FR10: a delivered branch reachable only via origin (narrow fetch, remote-tracking ref) is
    // recovered the same way — <ref>^ resolves against the remote-tracking ref just as it does
    // against a local one, so a fresh instance that never held the task can still reconcile it.
    def "recovers a delivered branch reachable only via origin (remote-tracking ref)"() {
        given: 'a delivered task pushed to a bare origin, and a fresh clone that lacks the branch locally'
        def bare = initBareRepo(tempDir, 'origin.git')
        runner.run(cloneDir, 'remote', 'add', 'origin', bare.toString())
        runner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository.createTask(context('PROJ-6'), null)
        def finalState = TaskState.atStageStart('implement')
        persistOneRound('PROJ-6', finalState)
        repository.recordOutcome('PROJ-6', new TaskOutcome.Completed(finalState))
        runner.run(worktreesRoot.resolve('clone').resolve('PROJ-6'), 'push', 'origin', 'gnomish/PROJ-6')

        def observerClone = tempDir.resolve('observer-clone')
        runner.run(tempDir, 'clone', '--branch', 'main', '--single-branch', bare.toString(), observerClone.toString())

        when:
        def delivered = new DeliveredBranchReader(runner).read(observerClone, 'PROJ-6')

        then: 'the delivered state is recovered via the remote-tracking ref, no local branch created'
        delivered.context().taskId() == 'PROJ-6'
        delivered.finalState() == finalState
        runner.run(observerClone, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-6').exitCode() != 0
    }

    // FR10: a reconcile can only be asked for a task whose branch exists; a genuinely absent branch
    // is a defect surfaced as GitTaskRepositoryException, not a silent empty read.
    def "throws when no branch exists for the task"() {
        when:
        reader.read(cloneDir, 'NOPE-9')

        then:
        thrown(GitTaskRepositoryException)
    }

    // FR10: the branch exists but its pre-cleanup commit does not carry the state files (a
    // non-Completed branch has no cleanup commit, so the parent is an unrelated commit) — surfaced
    // as BranchStateFileMissingException, distinct from "branch not found".
    def "throws BranchStateFileMissingException when the parent commit lacks the state files"() {
        given: 'a fresh task branch with a single commit — its parent is the base, carrying no .gnomish-task/'
        repository.createTask(context('PROJ-2'), null)

        when:
        reader.read(cloneDir, 'PROJ-2')

        then:
        thrown(BranchStateFileMissingException)
    }
}
