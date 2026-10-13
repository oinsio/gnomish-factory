package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.branch.TipRelation
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of harden-task-branch-contract: delivery is a fact about the task's own branch. A cleanup
 * commit an earlier task left in the base — its branch merged with history — is not this task's
 * delivery, so a live task forked from that base must not read as delivered.
 *
 * <p>F122, FR13 of tighten-spec-contract (design D13): delivery is "the registered commit is the
 * cleanup commit" — no history is searched, so neither a base's history nor a gnome commit carrying
 * the cleanup subject can deliver a task. Every write here goes through the factory's own writers,
 * which register what they commit, so a writer that stopped registering turns this spec red.
 */
class DeliveryAncestrySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    RegisteredClone registeredClone
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        commitAll(cloneDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
    }

    private void create(String taskId) {
        repository.createTask(
                new TaskContext(taskId, UntrustedText.tracker('Fix'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
    }

    /** Whether the branch of {@code taskId} reads as delivered, through the classification's own source. */
    private boolean delivered(String taskId) {
        RefTipSource.at(runner, cloneDir, taskId, 'refs/heads/gnomish/' + taskId).registeredCleanup()
    }

    /** Runs PROJ-1 to delivery and merges its branch into the base with its history (no squash). */
    private void deliverAndMergeEarlierTask(String mergeMode = '--no-ff') {
        create('PROJ-1')
        def worktree = registeredClone.worktrees().resolve('PROJ-1')
        def trace = new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        new GitAttemptPersistence(runner, worktree, 'PROJ-1', ClaimEpochSource.NONE)
                .persist('PROJ-1', TaskState.atStageStart('implement'), trace)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')), TrackerWrite.OWED)
        repository.finishCleanup('PROJ-1')
        def merge = runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
                'merge', mergeMode, '-m', 'Merge PR: PROJ-1', 'gnomish/PROJ-1')
        assert merge.exitCode() == 0
    }

    // Scenario: lifecycle/task-branch-contract / A cleanup commit inherited from the base does not deliver a live task
    def "FR1: a live task forked from a base that holds an earlier task's cleanup is not delivered"() {
        given: 'an earlier task delivered and merged into the base with its history'
        deliverAndMergeEarlierTask()

        when: 'a new task starts from that base'
        create('PROJ-2')

        then: 'the new branch has no cleanup commit of its own'
        !delivered('PROJ-2')
    }

    // Scenario: lifecycle/task-branch-contract / A base merged into a live task after it started does not deliver it
    def "FR1: a base with an earlier task's cleanup merged into a live task branch does not deliver it"() {
        given: 'a live task, and an earlier task delivered and merged into the base afterwards'
        create('PROJ-2')
        deliverAndMergeEarlierTask()

        when: 'the base is merged into the live task branch'
        def worktree = registeredClone.worktrees().resolve('PROJ-2')
        def merge = runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
                'merge', '--no-ff', '-m', 'Merge base into PROJ-2', currentBranch(cloneDir))
        assert merge.exitCode() == 0

        then:
        !delivered('PROJ-2')
    }

    // Scenario: lifecycle/task-branch-contract / A branch ref with no STARTED commit is Bare
    def "FR1: a branch with no STARTED commit of its own is not delivered by its base"() {
        given: 'an earlier task delivered and merged into the base'
        deliverAndMergeEarlierTask()

        when: 'a bare branch points at that base'
        assert runner.run(cloneDir, 'branch', 'gnomish/PROJ-3', 'HEAD').exitCode() == 0

        then: 'it has no register, so nothing is read at all, and its tip carries no envelope'
        !delivered('PROJ-3')
        RefTipSource.at(runner, cloneDir, 'PROJ-3', 'refs/heads/gnomish/PROJ-3').tipRelation() ==
                new TipRelation.NoRegister('refs/gnomish-factory/PROJ-3', currentHead(cloneDir), false)
    }

    def "FR14: a bare branch on a base that fast-forwarded an earlier task's branch is not delivered"() {
        given: "an earlier task delivered and fast-forwarded into the base: its STARTED and cleanup commits sit on the base's first-parent line"
        deliverAndMergeEarlierTask('--ff-only')

        when: 'a bare branch points at that base'
        assert runner.run(cloneDir, 'branch', 'gnomish/PROJ-3', 'HEAD').exitCode() == 0

        then:
        !delivered('PROJ-3')
    }

    // F122: delivery is read from the registered commit, a commit the gnome cannot name, so a
    // gnome commit carrying the cleanup subject — as the subject or anywhere in its message — above
    // the register delivers nothing.
    // Scenario: lifecycle/task-branch-contract / A gnome commit carrying the cleanup subject does not deliver the task
    def "F122: a gnome commit carrying the cleanup subject does not deliver the task (#message)"() {
        given: 'a live task'
        create('PROJ-2')
        def worktree = registeredClone.worktrees().resolve('PROJ-2')

        when: 'the gnome commits with the cleanup subject somewhere in its message'
        new File(worktree.toFile(), 'b.txt').text = 'work'
        assert runner.run(worktree, 'add', 'b.txt').exitCode() == 0
        assert runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
        'commit', '-m', message).exitCode() == 0

        then:
        !delivered('PROJ-2')
        new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure()).classifyShape(cloneDir, 'PROJ-2') ==
                new BranchShape.Created()

        where:
        message << [
            'fix\n\ngnomish: cleanup',
            'gnomish: cleanup'
        ]
    }

    def "FR1: the earlier task's own branch still reads as delivered"() {
        when:
        deliverAndMergeEarlierTask()

        then:
        delivered('PROJ-1')
    }
}
