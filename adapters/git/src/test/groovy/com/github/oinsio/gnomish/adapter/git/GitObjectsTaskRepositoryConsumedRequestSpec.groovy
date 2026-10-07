package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.*
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
 * FR14 of make-checkpoint-gate-durable: each of the bare-object medium's three outcome-clearing
 * writes drops {@code .gnomish-task/decisions/} from the tree of its own commit, and a tip without
 * the directory writes as before. The host twin is {@link GitTaskRepositoryConsumedRequestSpec}.
 */
class GitObjectsTaskRepositoryConsumedRequestSpec extends Specification implements BareGitRepoFixture {

    private static final BasePin PIN = new BasePin('base', BaseRefKind.BRANCH, BaseRule.EXPLICIT_ARGUMENT)

    private static final String REF = 'refs/heads/gnomish/PROJ-1'

    private static final Position.AwaitingApproval GATE = new Position.AwaitingApproval('implement')

    @TempDir
    Path tempDir

    Path bareDir
    GitObjectsTaskRepository repository

    def setup() {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')
        bareDir = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bareDir.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        repository = new GitObjectsTaskRepository(
                GitObjects.open(bareDir, Files.createDirectories(tempDir.resolve('index'))),
                new CommitIdentity('gnomish-factory', 'gnomish-factory@localhost'),
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC),
                ClaimEpochSource.NONE,
                DenialCursorSource.NONE)
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
        TaskStart.commit(bareDir, 'refs/heads/base'), PIN, gated())
        repository.recordOutcome('PROJ-1', new TaskOutcome.Paused(gated(), 'implement'), TrackerWrite.NONE)
    }

    /** Pushes a request onto the tip through a throwaway clone, as a harvested round that asked would. */
    private void requestOnTip() {
        Path work = tempDir.resolve('edit-' + System.nanoTime())
        seedClone(tempDir, bareDir.toString(), work)
        gitOutput(work, 'checkout', '-B', 'gnomish/PROJ-1', 'origin/gnomish/PROJ-1')
        Path request = work.resolve('.gnomish-task/decisions/implement-a0-0123abcd.json')
        Files.createDirectories(request.parent)
        Files.writeString(request, '{"question":"which?"}')
        commitAll(work, 'round asked')
        gitOutput(work, 'push', 'origin', 'HEAD:' + REF)
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

    private String files(String rev) {
        gitOutput(bareDir, 'ls-tree', '-r', '--name-only', rev)
    }

    private boolean carriesDecisions(String rev) {
        files(rev).readLines().any { it.startsWith('.gnomish-task/decisions/') }
    }

    private int commitCount() {
        gitOutput(bareDir, 'rev-list', '--count', REF) as int
    }

    def "FR14: #kind drops decisions/ from its own commit's tree, the tip before it still carries it"() {
        given:
        parked()
        requestOnTip()
        assert carriesDecisions(REF)
        def before = commitCount()

        when:
        write(kind)

        then: 'one commit: the outcome-clearing write and the removal together'
        commitCount() == before + 1
        !carriesDecisions(REF)
        carriesDecisions(REF + '~1')

        and: 'only the request left: the envelope and the work tree stay'
        files(REF).readLines().toSet() == files(REF + '~1').readLines().findAll {
            !it.startsWith('.gnomish-task/decisions/')
        }.toSet()

        where:
        kind << [
            'appendDecision',
            'approveCheckpoint',
            'resumeFrom'
        ]
    }

    def "FR14: #kind over a tip without decisions/ writes unchanged — one commit, the same tree paths"() {
        given:
        parked()
        def before = commitCount()
        def filesBefore = files(REF)

        when:
        write(kind)

        then:
        commitCount() == before + 1
        files(REF) == filesBefore
        !carriesDecisions(REF)

        where:
        kind << [
            'appendDecision',
            'approveCheckpoint',
            'resumeFrom'
        ]
    }
}
