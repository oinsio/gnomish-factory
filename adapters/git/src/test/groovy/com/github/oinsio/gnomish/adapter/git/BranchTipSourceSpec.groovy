package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR5 of harden-task-branch-contract: the three media of the tip-reader seam — a worktree's
 * own {@code HEAD}, a named ref of a clone, and bare objects — answer the same two questions the
 * same way, and every one of them reads the tip rather than the files on disk.
 */
class BranchTipSourceSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    Path worktreesRoot
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        commitAll(cloneDir)
        worktreesRoot = tempDir.resolve('worktrees')
        repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
        repository.createTask(new TaskContext('PROJ-1', 'Fix the thing', 'Body', []), null, TaskState.atStageStart('implement'))
    }

    private Path worktree(String taskId = 'PROJ-1') {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    private BranchTipSource bareObjects(String taskId = 'PROJ-1') {
        def gitObjects = GitObjects.open(cloneDir.resolve('.git'), tempDir.resolve('objtmp'))
        new BareObjectsTipSource(gitObjects, gitObjects.resolveRef("gnomish/$taskId").orElseThrow())
    }

    private List<BranchTipSource> allSources(String taskId = 'PROJ-1') {
        [
            new WorktreeTipSource(runner, worktree(taskId)),
            new RefTipSource(runner, cloneDir, "gnomish/$taskId"),
            bareObjects(taskId)
        ]
    }

    // FR1: a file present at the tip reads back identically through all three media.
    def "every medium reads a file the tip carries"() {
        expect:
        allSources().every {
            it.readAtTip('.gnomish-task/task.json').orElse('').contains('PROJ-1')
        }
    }

    // FR1: absence is an empty answer, not a failure — the classifier decides what absence means.
    // The absent path is a decision file: the STARTED commit carries task.json AND state.json
    // (FR3), so neither of those is absent on any live branch any more.
    def "every medium reports an absent file as empty"() {
        expect:
        allSources().every {
            it.readAtTip('.gnomish-task/decisions/implement-a0.json').isEmpty()
        }
    }

    // FR3: the STARTED commit carries the initial state.json beside task.json, so a tip read
    // right after branch creation already answers the classifier's state question.
    def "every medium reads the initial state.json the STARTED commit carries"() {
        expect:
        allSources().every {
            it.readAtTip('.gnomish-task/state.json').orElse('').contains('implement')
        }
    }

    // FR5: the worktree source reads HEAD, never the dirty file beside it — the half-written
    // state.json of a crashed instance must never reach a reader.
    def "the worktree medium reads the tip, not the dirty working copy"() {
        given: 'a worktree whose task.json on disk was overwritten with garbage'
        Files.writeString(worktree().resolve('.gnomish-task').resolve('task.json'), '{ truncated')

        when:
        def read = new WorktreeTipSource(runner, worktree()).readAtTip('.gnomish-task/task.json')

        then:
        read.orElse('').contains('PROJ-1')
    }

    // FR13: the tenure's epoch rides every commit as a trailer, and all three media read it back
    //     — the branch half of the fence works whichever way a reader reaches the tip.
    def "every medium reads the claim epoch stamped on the tip"() {
        given: 'a task branch created by an instance holding the tenure epoch 4711'
        def held = { String id ->
            Optional.of(new ClaimEpoch(4711))
        } as ClaimEpochSource
        new GitTaskRepository(runner, cloneDir, worktreesRoot, held)
                .createTask(new TaskContext('PROJ-2', 'Fix the other thing', 'Body', []),
                null, TaskState.atStageStart('implement'))

        expect:
        allSources('PROJ-2').every {
            it.tipEpoch().orElse(null) == new ClaimEpoch(4711)
        }
    }

    // FR13: a tip written with no tenure carries no stamp, and that is a legal answer — such a tip
    //     stands outside the fence rather than reading as stale.
    def "every medium reports no epoch for an unstamped tip"() {
        expect:
        allSources().every { it.tipEpoch().isEmpty() }
    }

    // FR1: delivery is a history question, so every medium answers "not delivered" for a live branch.
    def "no medium reports delivery before cleanup"() {
        expect:
        allSources().every { !it.cleanupCommitInHistory() }
    }

    // FR1: after the cleanup commit each medium finds it — including after further commits land on
    // top, which is why the search walks history instead of looking at tip^.
    def "every medium finds the cleanup commit in history, even under later commits"() {
        given: 'a completed task whose branch gained a human commit after cleanup'
        def persistence = new GitAttemptPersistence(runner, worktree(), 'PROJ-1', ClaimEpochSource.NONE)
        def trace = new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        persistence.persist('PROJ-1', TaskState.atStageStart('implement'), trace)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')
        Files.writeString(worktree().resolve('note.txt'), 'after cleanup')
        commitAll(worktree(), 'a human commit after cleanup')

        expect:
        allSources().every { it.cleanupCommitInHistory() }
    }
}
