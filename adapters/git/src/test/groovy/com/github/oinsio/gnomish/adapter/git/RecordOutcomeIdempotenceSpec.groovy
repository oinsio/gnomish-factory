package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of make-run-headless (design D8, "Idempotence"; crash-consistency item 8), task 2.6: {@code
 * recordOutcome} on a tip that already carries the identical {@code task.json} makes no commit and
 * returns normally — on both media, decided by {@code CommittedTaskJson#carries} before any commit
 * step. The reachable case is a {@code --resume} without {@code --decision} over an {@code
 * AttemptsExhausted} park whose rerun exhausts the same limit: the reset lives in memory only, so
 * the re-park is byte for byte the park already on the tip. A park that differs commits once, as
 * before.
 */
class RecordOutcomeIdempotenceSpec extends Specification implements BareGitRepoFixture {

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/' + TASK_ID
    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    /** The repository the branch ref is read from — the clone on the host, the bare origin for objects. */
    Path refRepo

    def "FR10: a second identical #kind park on the #medium medium leaves the tip as it is, without failing"() {
        given: 'a task parked once'
        TaskLifecycleStore repository = repositoryFor(medium)
        repository.recordOutcome(TASK_ID, outcome, TrackerWrite.NONE)
        def parkTip = tip()
        def parkCount = commitCount()

        when: 'the very same park is recorded again'
        repository.recordOutcome(TASK_ID, outcome, TrackerWrite.NONE)

        then: 'no exception, no new commit: the tip already carries the record'
        notThrown(Exception)
        tip() == parkTip
        commitCount() == parkCount

        where:
        medium | kind | outcome
        'host' | 'escalated' | escalated()
        'host' | 'paused' | paused()
        'objects' | 'escalated' | escalated()
        'objects' | 'paused' | paused()
    }

    def "FR10: a differing park on the #medium medium commits once more"() {
        given: 'a task parked once, with the attempts exhausted'
        TaskLifecycleStore repository = repositoryFor(medium)
        repository.recordOutcome(TASK_ID, escalated(), TrackerWrite.NONE)
        def parkTip = tip()
        def parkCount = commitCount()

        when: 'a park of a different content is recorded'
        repository.recordOutcome(TASK_ID, paused(), TrackerWrite.NONE)

        then: 'exactly one new commit carries it'
        tip() != parkTip
        commitCount() == parkCount + 1
        readTaskJson().contains('"paused"')

        where:
        medium << ['host', 'objects']
    }

    private static TaskOutcome escalated() {
        new TaskOutcome.Escalated(TaskState.atStageStart('implement'), new EscalationReport.AttemptsExhausted(1))
    }

    private static TaskOutcome paused() {
        new TaskOutcome.Paused(TaskState.atStageStart('implement'), 'implement')
    }

    private static TaskContext context() {
        new TaskContext(TASK_ID, UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body text'), [])
    }

    /** A repository of the given medium with the task branch already started, so a park can follow. */
    private TaskLifecycleStore repositoryFor(String medium) {
        medium == 'host' ? hostRepository() : objectsRepository()
    }

    private TaskLifecycleStore hostRepository() {
        Path cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir)
        refRepo = cloneDir
        def repository = new GitTaskRepository(
                runner, RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir), ClaimEpochSource.NONE, new VirtualClock())
        repository.createTask(context(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
        repository
    }

    private TaskLifecycleStore objectsRepository() {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work)
        Path bareDir = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bareDir.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        refRepo = bareDir
        Path indexDir = tempDir.resolve('index')
        Files.createDirectories(indexDir)
        def repository = new GitObjectsTaskRepository(
                GitObjects.open(bareDir, indexDir),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                ClaimEpochSource.NONE,
                DenialCursorSource.NONE)
        repository.createTask(context(), TaskStart.commit(bareDir, 'refs/heads/base'), PIN, TaskState.atStageStart('implement'))
        repository
    }

    private String tip() {
        gitOutput(refRepo, 'rev-parse', BRANCH).trim()
    }

    private int commitCount() {
        gitOutput(refRepo, 'rev-list', '--count', BRANCH).trim() as int
    }

    private String readTaskJson() {
        gitOutput(refRepo, 'show', "${BRANCH}:.gnomish-task/task.json")
    }
}
