package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.CommitMetadata
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Instant

/**
 * The host task flow the invocation-policy identity specs drive through the production adapters
 * (design D8 of own-git-invocation-policy): the lifecycle start — branch creation, {@code worktree
 * add}, the STARTED commit and its register transaction — and a round persisted in the worktree —
 * {@code commit-tree}, then the register moved by {@code update-ref --stdin} — and a commit
 * written through the git-object library. One flow, driven against each posture a spec plants (the
 * adversarial local configuration, a hook defined in the configuration), so every posture is tried
 * on the same git paths.
 *
 * <p>FR8, FR9 of own-git-invocation-policy.
 */
final class InvocationTaskFlow {

    static final String TASK = 'INV-1'
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)

    private final GitProcessRunner runner
    private final RegisteredClone registeredClone
    private final Path clone
    private final Path scratch

    InvocationTaskFlow(GitProcessRunner runner, RegisteredClone registeredClone, Path scratch) {
        this.runner = runner
        this.registeredClone = registeredClone
        this.clone = registeredClone.clonePath()
        this.scratch = scratch
    }

    /** The lifecycle start: branch, worktree add, the STARTED commit and its register transaction. */
    void startTask() {
        new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock()).createTask(
                new TaskContext(TASK, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                TaskStart.commit(clone, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
    }

    /** A round of {@code stage} persisted in the task worktree, its register moved in one transaction. */
    void persistRound(String stage) {
        Path worktree = registeredClone.worktrees().resolve(TASK)
        new File(worktree.toFile(), 'gnome.txt').text = 'gnome wrote this'
        new GitAttemptPersistence(runner, worktree, TASK, ClaimEpochSource.NONE)
                .persist(TASK, TaskState.atStageStart(stage), new ToolTrace(new AttemptKey(TASK, stage, 1), []))
    }

    /** The commit the task's factory register names. */
    String registeredCommit() {
        def result = runner.run(clone, 'rev-parse', FactoryRegisterRefs.of(TASK).name())
        assert result.exitCode() == 0: "rev-parse of the register failed: ${result.stderr()}"
        result.stdout().forParsing().trim()
    }

    /** A commit over {@code parent} with {@code path} set to {@code content}, written by the git-object library. */
    String forge(String parent, String path, String content) {
        def identity = new CommitIdentity('forger', 'forger@localhost')
        def at = Instant.parse('2026-10-20T00:00:00Z')
        GitObjects.open(clone.resolve('.git'), scratch).commit(new CommitRequest('refs/heads/forged',
                Optional.empty(), ObjectId.of(parent), [
                    new TreeEdit.PutFile(path, content.bytes)
                ],
                new CommitMetadata(identity, at, identity, at, 'forged'))).hex()
    }
}
