package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A task in progress over a real repository, written through the production store — every closing
 * commit registered (design D13 of tighten-spec-contract) — with the gnome's own commit on the
 * worktree's branch, above the register, one call away. Shared by the register-read specs.
 */
trait RegisteredRoundFixture implements BareGitRepoFixture, RegisteredBranchFixture {

    static final String TASK = 'PROJ-1'
    static final String TIP_REF = 'refs/heads/gnomish/PROJ-1'

    abstract Path getTempDir()

    GitProcessRunner runner = new GitProcessRunner()
    GitTaskStore store = new GitTaskStore(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure(), new VirtualClock())
    GitTaskBranches branches = new GitTaskBranches(runner, ClaimEpochSource.NONE, VirtualTimeGitRetries.gitInfrastructure())
    Path cloneDir
    RegisteredClone registeredClone

    /** A created task with one recorded round, both commits registered: the shape is InProgress. */
    void setupRegisteredRound() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        commit(cloneDir, 'a.txt', 'first')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        store.taskRepository(registeredClone).createTask(
                new TaskContext(TASK, UntrustedText.tracker('Fix it'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
        def round = new AttemptRecord(0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
        ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none())
        store.attemptPersistence(worktree(), TASK).persist(TASK,
                TaskState.atStageStart('implement').recordQualityFailure(round),
                new ToolTrace(new AttemptKey(TASK, 'implement', 0), []))
    }

    Path worktree() {
        registeredClone.worktrees().resolve(TASK)
    }

    /** The gnome rewrites one envelope file in its worktree and commits it; the register stays. */
    void gnomeRewrites(String file, Closure<?> edit) {
        def path = worktree().resolve('.gnomish-task').resolve(file)
        def json = TaskStateJson.mapper().readValue(path.toFile(), Map)
        edit.call(json)
        Files.writeString(path, TaskStateJson.mapper().writeValueAsString(json))
        commitAll(worktree(), 'wip: progress')
    }

    BranchShape shape() {
        branches.classifyShape(cloneDir, TASK)
    }
}
