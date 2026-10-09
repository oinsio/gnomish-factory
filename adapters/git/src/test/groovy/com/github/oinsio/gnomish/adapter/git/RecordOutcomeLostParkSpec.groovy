package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
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
 * FR2, FR11 of make-checkpoint-gate-durable (task 4.4; kill window "after the round commit, before
 * the park commit"): a tip at {@code AwaitingApproval(stage)} with {@code outcome} null — the round
 * landed, the park did not. The terminal boundary that receives the Engine's {@code Paused(stage)}
 * records the park through {@code recordOutcome}: on a tip lacking it, exactly one commit carrying
 * the {@code paused} outcome and leaving {@code state.json} at the gate; on a tip already carrying
 * that park, a content no-op — no second commit. Both media, each over a bare origin, for both the
 * manual run's record ({@link TrackerWrite#NONE}) and {@code take}'s intent ({@link
 * TrackerWrite#OWED}). The boundaries themselves are pinned by {@code RunTerminalBoundaryGateParkSpec}
 * and {@code TakeTerminalBoundaryGateParkSpec} in {@code :application}.
 */
class RecordOutcomeLostParkSpec extends Specification implements BareGitRepoFixture {

    private static final String TASK_ID = 'PROJ-1'
    private static final String BRANCH = 'gnomish/' + TASK_ID
    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    /** The state a passing {@code manual} round of {@code implement} commits: at the gate, its pass recorded. */
    private static final TaskState GATED = new TaskState(new Position.AwaitingApproval('implement'), 1, [
        new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(), JudgeUsage.none(), [],
        Stop.none())
    ], ExecutorUsage.none())

    @TempDir
    Path tempDir

    /** The repository the branch ref is read from — the clone on the host, the bare origin for objects. */
    Path refRepo

    def "FR2, FR11: a lost park at a gate on the #medium medium is recorded in one commit, and re-recording it adds none (#trackerWrite)"() {
        given: 'a tip at the gate whose park was never recorded'
        TaskLifecycleStore repository = repositoryFor(medium)
        def roundCount = commitCount()
        def gateState = readEnvelope('state.json')

        when: 'the terminal boundary records the Paused the Engine returned for the gate'
        def paused = new TaskOutcome.Paused(GATED, 'implement')
        repository.recordOutcome(TASK_ID, paused, trackerWrite)

        then: 'one commit carries the park; state.json still says the gate'
        commitCount() == roundCount + 1
        tipRecord().outcome() == new RecordedOutcome.Paused('implement')
        tipRecord().trackerWritePending() == (trackerWrite == TrackerWrite.OWED)
        readEnvelope('state.json') == gateState
        def parkTip = tip()

        when: 'a second pickup reaches the boundary again over the parked tip'
        repository.recordOutcome(TASK_ID, new TaskOutcome.Paused(GATED, 'implement'), trackerWrite)

        then: 'no second commit: the tip already carries that park'
        notThrown(Exception)
        tip() == parkTip
        commitCount() == roundCount + 1

        where:
        medium | trackerWrite
        'host' | TrackerWrite.NONE
        'host' | TrackerWrite.OWED
        'objects' | TrackerWrite.NONE
        'objects' | TrackerWrite.OWED
    }

    private static TaskContext context() {
        new TaskContext(TASK_ID, UntrustedText.tracker('Fix the thing'), UntrustedText.tracker('Body text'), [])
    }

    private TaskLifecycleStore repositoryFor(String medium) {
        medium == 'host' ? hostRepository() : objectsRepository()
    }

    /** The host medium: a factory clone of a bare origin, the task branch in the clone's worktree. */
    private TaskLifecycleStore hostRepository() {
        Path cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir)
        addOrigin(cloneDir, tempDir)
        refRepo = cloneDir
        def repository = new GitTaskRepository(new GitProcessRunner(),
                RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir), ClaimEpochSource.NONE)
        repository.createTask(context(), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), GATED)
        repository
    }

    /** The container medium: bare-object writes straight into the origin. */
    private TaskLifecycleStore objectsRepository() {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work)
        Path bareDir = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bareDir.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        refRepo = bareDir
        def repository = new GitObjectsTaskRepository(
                GitObjects.open(bareDir, Files.createDirectories(tempDir.resolve('index'))),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                ClaimEpochSource.NONE,
                DenialCursorSource.NONE)
        repository.createTask(context(), TaskStart.commit(bareDir, 'refs/heads/base'), PIN, GATED)
        repository
    }

    private String tip() {
        gitOutput(refRepo, 'rev-parse', BRANCH).trim()
    }

    private int commitCount() {
        gitOutput(refRepo, 'rev-list', '--count', BRANCH).trim() as int
    }

    private String readEnvelope(String file) {
        gitOutput(refRepo, 'show', "${BRANCH}:.gnomish-task/${file}")
    }

    private TaskRecord tipRecord() {
        TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(readEnvelope('task.json'))))
    }
}
