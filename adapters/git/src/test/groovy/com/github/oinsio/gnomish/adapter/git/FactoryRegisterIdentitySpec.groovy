package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13): the identity the factory register exists for,
 * on a real bare origin. After every closing path of both media — the lifecycle writes (STARTED, an
 * outcome, the tracker-write receipt, the cleanup, an approval, a decision, a resumed write), the
 * host round commit, the sandboxed snapshot and state commit, and a salvage — the register and the
 * branch tip are one commit, in the clone and in origin. And the closing push is one atomic step: a
 * refusal of either ref lands neither.
 *
 * <p>The component specs of each push point assert their own register; only this one walks every
 * closing path over the production wiring and asserts the identity end to end (`testing.md`,
 * "Invariant specs across a flow").
 */
class FactoryRegisterIdentitySpec extends Specification implements LifecyclePushFixture, RegisteredBranchFixture {

    private static final String REGISTER = 'refs/gnomish-factory/T-1'

    @TempDir
    Path tempDir

    def setup() {
        initLifecyclePushFixture()
        git.run(cloneDir, 'branch', '-D', BRANCH)
    }

    /** The production wiring of the lifecycle store, as {@code LifecyclePushIntegrationSpec} builds it. */
    private TaskLifecycleStore lifecycle(String mode) {
        if (mode == 'host') {
            return new GitTaskStore(git, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock())
                    .taskRepository(RegisteredCloneFixture.resolvedOrRegistered(tempDir.resolve('home'), cloneDir))
        }
        new PushBestEffortTaskLifecycleStore(
                new GitObjectsTaskRepository(objects(), new VirtualClock(Instant.parse('2026-01-01T00:00:00Z')), ClaimEpochSource.NONE, DenialCursorSource.NONE),
                git, cloneDir, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock())
    }

    private GitObjects objects() {
        GitObjects.open(cloneDir.resolve('.git'), Files.createDirectories(tempDir.resolve('index')))
    }

    private void createTask(TaskLifecycleStore store) {
        store.createTask(new TaskContext(TASK_ID, UntrustedText.tracker('Fix it'), UntrustedText.tracker('Body'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
    }

    /** The tip of {@code ref} in {@code repo}, or null when it does not exist. */
    private String at(Path repo, String ref) {
        def read = git.run(repo, 'rev-parse', '--verify', '--quiet', ref)
        read.exitCode() == 0 ? read.stdout().forParsing().trim() : null
    }

    /** Register and branch tip are one commit, in the clone and in origin. */
    private boolean registeredEverywhere() {
        def tip = at(cloneDir, "refs/heads/${BRANCH}")
        tip != null && [
            at(cloneDir, REGISTER),
            at(origin, "refs/heads/${BRANCH}"),
            at(origin, REGISTER)
        ].every { it == tip }
    }

    /** The worktree the host store checked the task branch out in. */
    private Path hostWorktree() {
        def blocks = gitOutput(cloneDir, 'worktree', 'list', '--porcelain').split('\n\n')
        Path.of(blocks.find {
            it.contains("branch refs/heads/${BRANCH}")
        }.readLines().first() - 'worktree ')
    }

    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: every lifecycle closing commit of the #mode medium leaves register and branch tip one commit"() {
        given:
        def store = lifecycle(mode)
        def after = [:]

        when: 'the lifecycle runs to delivery, the identity read after each closing push'
        createTask(store)
        after.started = registeredEverywhere()
        store.recordOutcome(TASK_ID, new TaskOutcome.Completed(TaskState.atStageStart('implement')), TrackerWrite.OWED)
        after.outcome = registeredEverywhere()
        store.confirmTerminalWrite(TASK_ID)
        after.receipt = registeredEverywhere()
        store.finishCleanup(TASK_ID)
        after.cleanup = registeredEverywhere()

        then:
        after == [started: true, outcome: true, receipt: true, cleanup: true]

        where:
        mode << ['host', 'sandbox']
    }

    // The approval, the appended decision and the resumed write share the one decorator funnel
    //     the real drive above exercises; a delegate that advances the branch by one commit, as
    //     each of those writes does, shows each of them reaches it.
    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: an approval, an appended decision and a resumed write each move the register with the branch"() {
        given:
        git.run(cloneDir, 'branch', BRANCH)
        def delegate = Mock(TaskLifecycleStore)
        def store = new PushBestEffortTaskLifecycleStore(delegate, git, cloneDir, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock())

        when:
        write.call(store)

        then:
        1 * delegate."$method"(*_) >> {
            commitOnTaskBranch(name)
        }
        0 * delegate._
        registeredEverywhere()

        where:
        name | method | write
        'approval' | 'approveCheckpoint' | { TaskLifecycleStore s ->
            s.approveCheckpoint(TASK_ID, new Position.AwaitingApproval('implement'), TaskState.atStageStart('implement'))
        }
        'decision' | 'appendDecision' | { TaskLifecycleStore s ->
            s.appendDecision(TASK_ID, null, TaskState.atStageStart('implement'))
        }
        'resumed' | 'resumeFrom' | { TaskLifecycleStore s ->
            s.resumeFrom(TASK_ID, TaskState.atStageStart('implement'))
        }
    }

    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: the host round commit and a host salvage each leave register and branch tip one commit"() {
        given: 'a task created through the host store, worked in the worktree the store checked it out in'
        createTask(lifecycle('host'))
        def worktree = hostWorktree()
        def persistence = new GitAttemptPersistence(git, worktree, TASK_ID, ClaimEpochSource.NONE)

        when: 'a round closes over gnome work'
        Files.writeString(worktree.resolve('work.txt'), 'gnome work')
        persistence.persist(TASK_ID, TaskState.atStageStart('implement'), new ToolTrace(new AttemptKey(TASK_ID, 'implement', 0), []))
        def afterRound = registeredEverywhere()

        and: 'an interrupted round is salvaged and pushed, as revocation does'
        Files.writeString(worktree.resolve('leftover.txt'), 'half-done')
        new WorktreeSalvage(git, worktree, ClaimEpochSource.NONE).salvage(TASK_ID)
        new GitTaskBranches(git, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()).pushClosingBestEffort(worktree, TASK_ID)

        then:
        afterRound
        registeredEverywhere()
    }

    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: the sandboxed snapshot, state commit and salvage each move the register with the branch"() {
        given: 'a task created over bare objects, its box materialized and a round opened'
        createTask(lifecycle('sandbox'))
        def started = at(cloneDir, REGISTER)
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        def rounds = OpenedRound.reopen(new CurrentRound(), cloneDir, BRANCH)
        def push = new BestEffortClosingPush(git)
        def persistence = new PushBestEffortAttemptPersistence(
                new EnvironmentAttemptPersistence(box, git, cloneDir, objects(), rounds, ClaimEpochSource.NONE), push, cloneDir)

        when: 'the gnome half of the round closes: the snapshot is admitted and registered in the clone, not yet pushed'
        box.workingCopy.resolve('work.txt').toFile().text = 'gnome work'
        def snapshot = new EnvironmentRoundSnapshot(box, git, cloneDir, rounds).snapshot(TASK_ID, 'implement', 0)
        def registeredSnapshot = at(cloneDir, REGISTER)
        def originAtSnapshot = at(origin, REGISTER)

        and: 'the state commit closes the round with its closing push'
        persistence.persist(TASK_ID, TaskState.atStageStart('implement'), new ToolTrace(new AttemptKey(TASK_ID, 'implement', 0), []))
        def afterState = registeredEverywhere()

        and: 'an interrupted round is salvaged in-box and pushed, as the container revocation does'
        box.workingCopy.resolve('leftover.txt').toFile().text = 'half-done'
        new EnvironmentSalvage(box, ClaimEpochSource.NONE, git, cloneDir).salvage(TASK_ID)
        push.pushBestEffort(cloneDir, TASK_ID)

        then:
        registeredSnapshot == snapshot
        originAtSnapshot == started
        afterState
        registeredEverywhere()
    }

    // Scenario: git/git-task-persistence / Branch and register land together
    def "FR13: a closing push whose register origin refuses lands neither ref"() {
        given: 'a delivered closing commit, then an origin whose update hook refuses any register move'
        createTask(lifecycle('sandbox'))
        def delivered = at(origin, REGISTER)
        StandIn.link(origin.resolve('hooks').resolve('update'), 'hook-refuse-register')

        when: 'the next closing commit is registered and pushed'
        commitOnTaskBranch('outcome')
        registerTip(cloneDir, TASK_ID)
        def push = new ClosingPush(git)
        def result = push.push(cloneDir, TASK_ID)

        then: 'the push failed, and origin holds the branch and the register exactly where they were'
        result.exitCode() != 0
        at(origin, "refs/heads/${BRANCH}") == delivered
        at(origin, REGISTER) == delivered
    }

    // Scenario: git/git-task-persistence / Branch and register land together
    def "FR13: a closing push whose branch origin refuses moves no register"() {
        given: 'a delivered closing commit, then another writer moved origin branch off the local line'
        createTask(lifecycle('sandbox'))
        def delivered = at(origin, REGISTER)
        def other = tempDir.resolve('other')
        seedClone(tempDir, origin.toString(), other, '-q')
        gitOutput(other, 'checkout', '-q', BRANCH)
        commit(other, 'other.txt', 'someone else')
        gitOutput(other, 'push', '-q', 'origin', "${BRANCH}:${BRANCH}")
        def otherTip = at(origin, "refs/heads/${BRANCH}")

        when: 'the next local closing commit is registered and pushed'
        commitOnTaskBranch('outcome')
        registerTip(cloneDir, TASK_ID)
        def push = new ClosingPush(git)
        def result = push.push(cloneDir, TASK_ID)

        then: 'the non-fast-forward branch refused the whole push: the register did not move either'
        result.exitCode() != 0
        at(origin, "refs/heads/${BRANCH}") == otherTip
        at(origin, REGISTER) == delivered
    }
}
