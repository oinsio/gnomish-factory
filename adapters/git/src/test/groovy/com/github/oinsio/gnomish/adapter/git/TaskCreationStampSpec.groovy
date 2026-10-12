package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of supervise-daemon-loops-and-embed-dashboard: the task file's creation time is read from
 * the injected instant source on both task-branch media — the host medium (a registered clone and
 * its worktree, {@link GitTaskRepository}) and the environment medium (bare objects, {@link
 * GitObjectsTaskRepository}). Both are built on one virtual source set far from the wall clock, so
 * a medium stamping on a clock of its own writes some other instant.
 */
class TaskCreationStampSpec extends Specification implements BareGitRepoFixture {

    private static final Instant CREATED = Instant.parse('2031-03-04T05:06:07Z')

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def clock = new VirtualClock(CREATED)

    private static TaskContext context(String taskId) {
        new TaskContext(taskId, UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body'), [])
    }

    private static Instant createdAt(String taskJson) {
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(taskJson))).createdAt()
    }

    private Instant createdOnHost() {
        Path clone = initWorkingRepo(tempDir, 'clone')
        Files.writeString(clone.resolve('a.txt'), 'first')
        commitAll(clone)
        def registered = RegisteredCloneFixture.registered(tempDir.resolve('home'), clone)
        new GitTaskRepository(runner, registered, ClaimEpochSource.NONE, clock).createTask(context('HOST-1'),
                TaskStart.commit(clone, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        createdAt(gitOutput(clone, 'show', 'gnomish/HOST-1:' + EnvelopePaths.TASK_JSON_PATH))
    }

    private Instant createdInEnvironment() {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work)
        Path bare = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bare.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        def repository = new GitObjectsTaskRepository(
                GitObjects.open(bare, Files.createDirectories(tempDir.resolve('index'))),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                clock,
                ClaimEpochSource.NONE,
                DenialCursorSource.NONE)
        repository.createTask(context('ENV-1'), TaskStart.commit(bare, 'refs/heads/base'),
                new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT), TaskState.atStageStart('implement'))
        createdAt(gitOutput(bare, 'show', 'refs/heads/gnomish/ENV-1:' + EnvelopePaths.TASK_JSON_PATH))
    }

    // FR20: the two media take the creation time from the same injected source.
    // Scenario: core/time-source / Both task-branch media stamp alike
    def "FR20: a task created on the host medium and on the environment medium carries the virtual instant"() {
        expect:
        createdOnHost() == CREATED
        createdInEnvironment() == CREATED
    }
}
