package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The host cleanup commit, {@link GitTaskRepository#finishCleanup}, written by the one writer both
 * media share — {@link GitObjectsTerminalCommits#cleanUp} over bare objects in the clone's {@code
 * .git} (design D8 of fix-terminal-receipt-convergence). Rehomed from the specs of the former host
 * writer, which removed the directory in the task worktree: its staged-removal matrix went with the
 * worktree, and the one no-op left is a tip that carries no envelope.
 *
 * <p>FR15, M4 of add-git-workflow; FR10 of harden-task-branch-contract; FR4, NFR-R1 of
 * fix-terminal-receipt-convergence.
 */
class GitTaskRepositoryCleanupSpec extends Specification implements BareGitRepoFixture {

    private static final String TASK = 'PROJ-1'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    RegisteredClone registeredClone

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
    }

    // Scenario: git/git-task-persistence / Clean tip, full history
    def "FR15/M4: finishCleanup adds the cleanup commit removing .gnomish-task/ from the tip, full history preserved"() {
        given: 'a task with a lifecycle commit before completion, to prove earlier history stays reachable'
        def repository = completed(ClaimEpochSource.NONE) {
            it.appendDecision(TASK, new Decision('proceed', 'implement', 'operator', null), TaskState.atStageStart('implement'))
        }
        def commitsBefore = git('rev-list', '--count', branch()) as int

        when:
        repository.finishCleanup(TASK)

        then: 'the tip carries no .gnomish-task/ in its tree'
        git('ls-tree', branch(), '--', '.gnomish-task') == ''

        and: 'the cleanup commit is the tip, the Completed outcome commit its parent'
        git('log', '-1', '--format=%s', branch()) == ServiceCommitMessages.cleanup()
        git('log', '-1', '--format=%s', branch() + '~1') == ServiceCommitMessages.taskEvent(TaskLifecycleEvent.COMPLETED)

        and: 'the completed task.json is still readable from history'
        def historical = runner.run(cloneDir, 'show', "${branch()}~1:.gnomish-task/task.json").stdout()
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(historical)).outcome() instanceof RecordedOutcome.Completed

        and: 'one commit is appended on top of the history'
        (git('rev-list', '--count', branch()) as int) == commitsBefore + 1
    }

    // FR4, NFR-R1 of fix-terminal-receipt-convergence (design D8): one writer on both media, so the
    //     host cleanup commit carries what the container's does — the factory's identity, the
    //     tenure's epoch trailer — and lands with the factory register in one ref transaction.
    def "D8: the host cleanup commit is the factory's, stamped with the tenure and registered with the branch"() {
        given: 'a completed task cleaned by an instance holding the tenure epoch 4711'
        def repository = completed({ String id ->
            Optional.of(new ClaimEpoch(4711))
        } as ClaimEpochSource)

        when:
        repository.finishCleanup(TASK)

        then: 'author and committer are the factory identity, not the clone configuration'
        def factory = TaskLifecycleCommitWriter.FACTORY_IDENTITY
        git('log', '-1', '--format=%an <%ae>|%cn <%ce>', branch()) ==
                "${factory.name()} <${factory.email()}>|${factory.name()} <${factory.email()}>"

        and: 'the epoch trailer rides the message'
        git('log', '-1', '--format=%B', branch()).contains('Gnomish-Claim-Epoch: 4711')

        and: 'the register names the cleanup commit the moment the branch does'
        git('rev-parse', 'refs/gnomish-factory/' + TASK) == git('rev-parse', branch())
    }

    // Design D8: the medium is the clone's .git — the worktree is neither required nor touched. The
    //     disposal behind the commit removes it; a branch pass may clean a branch whose worktree is
    //     already gone, and the cleanup does not bring one back.
    // Scenario: git/git-task-persistence / The cleanup commit is built per mode
    def "D8: finishCleanup neither needs nor touches the task worktree"() {
        given: 'a completed task'
        def repository = completed(ClaimEpochSource.NONE)
        def worktree = registeredClone.worktrees().resolve(TASK)

        when: 'its worktree is cleaned up while it is still on disk'
        repository.finishCleanup(TASK)

        then: 'the cleanup landed at the tip, and the worktree is as it was: its disposal is the caller\'s'
        git('log', '-1', '--format=%s', branch()) == ServiceCommitMessages.cleanup()
        Files.exists(worktree.resolve('.gnomish-task/task.json'))

        when: 'another completed task whose worktree is already gone is cleaned up'
        def other = completed(ClaimEpochSource.NONE, 'PROJ-2')
        def otherWorktree = registeredClone.worktrees().resolve('PROJ-2')
        assert runner.run(cloneDir, 'worktree', 'remove', '--force', otherWorktree.toString()).exitCode() == 0
        other.finishCleanup('PROJ-2')

        then: 'the cleanup lands without bringing the worktree back'
        git('log', '-1', '--format=%s', branch('PROJ-2')) == ServiceCommitMessages.cleanup()
        !Files.exists(otherWorktree)
    }

    // FR10 of harden-task-branch-contract: running the destructive step twice equals running it once.
    // FR5 of harden-logging-observability: the second run writes nothing, so only a DEBUG line
    // distinguishes "already done" from "never ran".
    // Scenario: git/git-task-persistence / A tip without the state directory is a cleanup no-op
    def "FR10: finishCleanup on a tip that carries no envelope changes nothing, whatever the worktree holds"() {
        given: 'a cleaned task whose worktree still holds the envelope on disk'
        def repository = completed(ClaimEpochSource.NONE)
        repository.finishCleanup(TASK)
        def tip = git('rev-parse', branch())
        assert Files.exists(registeredClone.worktrees().resolve(TASK).resolve('.gnomish-task'))
        def logs = LogCaptureSupport.attach(GitObjectsTerminalCommits, Level.DEBUG)

        when:
        repository.finishCleanup(TASK)
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'the tip does not move'
        git('rev-parse', branch()) == tip

        and: 'one DEBUG line says so'
        events.size() == 1
        events[0].level == Level.DEBUG
        events[0].formattedMessage.contains("cleanup commit for task ${TASK} is a no-op")
    }

    /** A task created, optionally advanced by {@code between}, and recorded {@code Completed}. */
    private GitTaskRepository completed(ClaimEpochSource epochs, String taskId = TASK, Closure between = {}) {
        def repository = new GitTaskRepository(runner, registeredClone, epochs, new VirtualClock())
        repository.createTask(
                new TaskContext(taskId, UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
        between.call(repository)
        repository.recordOutcome(taskId, new TaskOutcome.Completed(TaskState.atStageStart('implement')), TrackerWrite.OWED)
        repository
    }

    private GitTaskRepository completed(ClaimEpochSource epochs, Closure between) {
        completed(epochs, TASK, between)
    }

    private static String branch(String taskId = TASK) {
        'refs/heads/gnomish/' + taskId
    }

    private String git(String... args) {
        runner.run(cloneDir, args).stdout().forParsing().trim()
    }
}
