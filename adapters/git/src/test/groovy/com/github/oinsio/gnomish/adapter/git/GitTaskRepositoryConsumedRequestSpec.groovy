package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14 of make-checkpoint-gate-durable: each of the host medium's three outcome-clearing writes
 * removes {@code .gnomish-task/decisions/} in its own commit, and a tip without the directory
 * writes as before. The bare-object twin is {@link GitObjectsTaskRepositoryConsumedRequestSpec}.
 */
class GitTaskRepositoryConsumedRequestSpec extends Specification implements BareGitRepoFixture {

    private static final Position.AwaitingApproval GATE = new Position.AwaitingApproval('implement')

    private static final String REQUEST = '.gnomish-task/decisions/implement-a0-0123abcd.json'

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    RegisteredClone registeredClone
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        repository = new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock())
    }

    /** At the gate, its passing round recorded — the state a passing {@code manual} round leaves. */
    private static TaskState gated() {
        new TaskState(GATE, 0, [
            new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [],
            ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none())
        ], ExecutorUsage.none())
    }

    private static TaskState pastGate() {
        def gate = gated()
        new TaskState(new Position.AtStage('verify'), gate.attemptsUsed(), gate.attempts(), gate.totals())
    }

    /** A task parked at the gate — a tip every one of the three writes admits. */
    private void parked() {
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), gated())
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(gated(), 'implement'), TrackerWrite.NONE)
    }

    /** Commits a request onto the tip, as a harvested round that asked would have left it. */
    private void requestOnTip() {
        Files.createDirectories(worktree().resolve(REQUEST).parent)
        Files.writeString(worktree().resolve(REQUEST), '{"question":"which?"}')
        runner.run(worktree(), 'add', '-A')
        runner.run(worktree(), 'commit', '-m', 'round asked')
    }

    private void write(String kind) {
        switch (kind) {
            case 'appendDecision':
                repository.appendDecision('PROJ-1', new Decision('proceed', 'implement', 'op', null), gated().resetAttempts())
                break
            case 'approveCheckpoint':
                repository.approveCheckpoint('PROJ-1', GATE, pastGate())
                break
            case 'resumeFrom':
                repository.resumeFrom('PROJ-1', gated().resetAttempts())
                break
        }
    }

    private Path worktree() {
        registeredClone.worktrees().resolve('PROJ-1')
    }

    private boolean tipCarriesDecisions() {
        !runner.run(worktree(), 'ls-tree', '-r', '--name-only', 'HEAD', '--', '.gnomish-task/decisions')
                .stdout().isBlank()
    }

    private int commitCount() {
        runner.run(worktree(), 'rev-list', '--count', 'HEAD').stdout().forParsing().trim() as int
    }

    private String tipFiles() {
        runner.run(worktree(), 'ls-tree', '-r', '--name-only', 'HEAD').stdout().forParsing()
    }

    def "FR14: #kind removes decisions/ in its own commit, the tip before it still carries it"() {
        given:
        parked()
        requestOnTip()
        assert tipCarriesDecisions()
        def before = commitCount()

        when:
        write(kind)

        then: 'one commit: the outcome-clearing write and the removal together'
        commitCount() == before + 1
        !tipCarriesDecisions()
        !Files.exists(worktree().resolve(REQUEST))

        and: 'the tip before the write still holds the request — the removal is the write, nothing earlier'
        !runner.run(worktree(), 'ls-tree', '-r', '--name-only', 'HEAD~1', '--', '.gnomish-task/decisions')
                .stdout().isBlank()

        and: 'nothing is left staged or dirty under decisions/ for a later commit to pick up'
        runner.run(worktree(), 'status', '--porcelain').stdout().isBlank()

        where:
        kind << [
            'appendDecision',
            'approveCheckpoint',
            'resumeFrom'
        ]
    }

    def "FR14: #kind over a tip without decisions/ writes unchanged — one commit, the same envelope"() {
        given:
        parked()
        def before = commitCount()
        def filesBefore = tipFiles()

        when:
        write(kind)

        then:
        commitCount() == before + 1
        tipFiles() == filesBefore
        !tipCarriesDecisions()

        where:
        kind << [
            'appendDecision',
            'approveCheckpoint',
            'resumeFrom'
        ]
    }

    def "FR14: #kind leaves no request the worktree held uncommitted, tracked-modified or untracked"() {
        given: 'a committed request modified in the worktree, and a second one never committed'
        parked()
        requestOnTip()
        Files.writeString(worktree().resolve(REQUEST), '{"question":"edited"}')
        Files.writeString(worktree().resolve(REQUEST).resolveSibling('stray.json'), '{}')

        when:
        write(kind)

        then:
        !tipCarriesDecisions()
        runner.run(worktree(), 'status', '--porcelain').stdout().isBlank()

        where:
        kind << [
            'appendDecision',
            'approveCheckpoint',
            'resumeFrom'
        ]
    }
}
