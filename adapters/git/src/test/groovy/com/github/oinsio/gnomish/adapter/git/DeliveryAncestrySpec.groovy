package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of harden-task-branch-contract: delivery is a fact about the task's own branch. A cleanup
 * commit an earlier task left in the base — its branch merged with history — is not this task's
 * delivery, so a live task forked from that base must not read as delivered.
 *
 * <p>FR14 of fix-operator-blockers (with its NFR-R4 and M6): delivery is searched only in the task's own
 * first-parent history after its STARTED commit (design D11).
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
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE)
    }

    private void create(String taskId) {
        repository.createTask(
                new TaskContext(taskId, UntrustedText.tracker('Fix'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
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

    def "FR1: a live task forked from a base that holds an earlier task's cleanup is not delivered"() {
        given: 'an earlier task delivered and merged into the base with its history'
        deliverAndMergeEarlierTask()

        when: 'a new task starts from that base'
        create('PROJ-2')

        then: 'the new branch has no cleanup commit of its own'
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommitInHistory()
        new GitShowTip(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommit().isEmpty()
    }

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
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommitInHistory()
    }

    def "FR1: a branch with no STARTED commit of its own is not delivered by its base"() {
        given: 'an earlier task delivered and merged into the base'
        deliverAndMergeEarlierTask()

        when: 'a bare branch points at that base'
        assert runner.run(cloneDir, 'branch', 'gnomish/PROJ-3', 'HEAD').exitCode() == 0

        then:
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-3').cleanupCommitInHistory()
    }

    def "FR14: a bare branch on a base that fast-forwarded an earlier task's branch is not delivered"() {
        given: "an earlier task delivered and fast-forwarded into the base: its STARTED and cleanup commits sit on the base's first-parent line"
        deliverAndMergeEarlierTask('--ff-only')

        when: 'a bare branch points at that base'
        assert runner.run(cloneDir, 'branch', 'gnomish/PROJ-3', 'HEAD').exitCode() == 0

        then:
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-3').cleanupCommitInHistory()
        new GitShowTip(runner, cloneDir, 'gnomish/PROJ-3').cleanupCommit().isEmpty()
    }

    // Known defect, not closed by FR14: delivery is still read from a commit message the gnome can
    // write on its own branch — even the exact subject. Closing it needs delivery bound to
    // something only the factory writes; this feature turns red the day that lands.
    @PendingFeature(reason = 'a gnome commit carrying the cleanup subject reads as delivery')
    def "a gnome commit carrying the cleanup subject does not deliver the task (#message)"() {
        given: 'a live task'
        create('PROJ-2')
        def worktree = registeredClone.worktrees().resolve('PROJ-2')

        when: 'the gnome commits with the cleanup subject somewhere in its message'
        new File(worktree.toFile(), 'b.txt').text = 'work'
        assert runner.run(worktree, 'add', 'b.txt').exitCode() == 0
        assert runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
        'commit', '-m', message).exitCode() == 0

        then:
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommitInHistory()

        where:
        message << [
            'fix\n\ngnomish: cleanup',
            'gnomish: cleanup'
        ]
    }

    def "FR14: a cleanup after a STARTED commit whose task.json #shape, read at #ref, delivers: #delivered"() {
        given: 'a branch whose nearest STARTED commit carries that task.json, with a cleanup after it'
        assert runner.run(cloneDir, 'checkout', '-q', '-b', 'gnomish/PROJ-9').exitCode() == 0
        def envelope = cloneDir.resolve('.gnomish-task').toFile()
        envelope.mkdirs()
        if (taskJson != null) {
            new File(envelope, 'task.json').text = taskJson
        } else {
            new File(envelope, 'other.txt').text = 'x'
        }
        commitAll(cloneDir, 'gnomish: task started')
        new File(envelope, 'other.txt').text = 'y'
        commitAll(cloneDir, 'gnomish: cleanup')
        assert runner.run(cloneDir, 'tag', 'v1').exitCode() == 0

        expect:
        new RefTipSource(runner, cloneDir, ref).cleanupCommitInHistory() == delivered

        where:
        shape | taskJson | ref | delivered
        'names this task' | taskJson('PROJ-9') | 'gnomish/PROJ-9' | true
        'names this task (full ref)'| taskJson('PROJ-9') | 'refs/heads/gnomish/PROJ-9' | true
        'names another task' | taskJson('PROJ-1') | 'gnomish/PROJ-9' | false
        'names a suffix of it' | taskJson('9') | 'gnomish/PROJ-9' | false
        'names this task (HEAD)' | taskJson('PROJ-9') | 'HEAD' | true
        'names this task (a tag)' | taskJson('PROJ-9') | 'v1' | false
        'names this task (commit)' | taskJson('PROJ-9') | 'gnomish/PROJ-9^{commit}' | false
        'is absent' | null | 'gnomish/PROJ-9' | false
        'is not JSON' | '{' | 'gnomish/PROJ-9' | false
        'has an unknown version' | '{"version":2,"taskId":"PROJ-9"}' | 'gnomish/PROJ-9' | false
        'pins a malformed base ref' | taskJson('PROJ-9', '"baseRef":"refs/heads/a..b"') | 'gnomish/PROJ-9' | false
        'names an unsanitizable id' | taskJson('...') | 'gnomish/PROJ-9' | false
    }

    private static String taskJson(String taskId, String extra = null) {
        '{"version":1,"taskId":"' + taskId + '","title":"t","body":"b","createdAt":"2026-07-18T09:00:00Z",' +
                '"baseCommit":"0000000000000000000000000000000000000000","decisions":[]' +
                (extra == null ? '' : ',' + extra) + '}'
    }

    def "FR1: the earlier task's own branch still reads as delivered"() {
        when:
        deliverAndMergeEarlierTask()

        then:
        new RefTipSource(runner, cloneDir, 'gnomish/PROJ-1').cleanupCommitInHistory()
    }
}
