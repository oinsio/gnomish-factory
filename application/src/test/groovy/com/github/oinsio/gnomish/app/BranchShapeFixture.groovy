package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Builds a task branch of a named shape on the seeded clone — every shape of the closed set the
 * classifier knows, legal or quarantined — the way the factory, or damage to its envelope, leaves
 * one (FR16, UX4 of harden-task-branch-contract). Real git through the host medium's own adapters.
 */
trait BranchShapeFixture implements SeededCloneFixture {

    /** The host medium's writer, claimless as a read-only command's seeding is. */
    TaskLifecycleStore repository() {
        TaskGitFixture.realClaimless().store().taskRepository(registeredClone)
    }

    void create(String taskId, TaskState state, List<Decision> decisions = []) {
        repository().createTask(new TaskContext(taskId, UntrustedText.tracker('Fix it'), UntrustedText.tracker('Body'), decisions),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), state)
    }

    void withRound(String taskId) {
        persistRound(taskId, TaskState.atStageStart('implement').recordUnburnedRound(round(0, AttemptRecord.Result.PASSED, 500, 100)))
    }

    /** A task with one round, then {@code damage} committed to its envelope as a broken factory writer would. */
    void damaged(String taskId, Closure damage) {
        withRound(taskId)
        def worktree = worktreeFor(taskId)
        damage(worktree.resolve('.gnomish-task'))
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'damage')
        // The damage is the factory's own record (a newer factory's version, a broken write), so it
        // is registered as its closing push would register it (design D13 of tighten-spec-contract):
        // damage above the register is the gnome's, and changes no shape.
        registerTip(cloneDir, taskId)
    }

    void finished(String taskId, TaskOutcome outcome, boolean cleanedUp = false) {
        withRound(taskId)
        repository().recordOutcome(taskId, outcome, TrackerWrite.OWED)
        if (cleanedUp) {
            repository().finishCleanup(taskId)
        }
    }

    static String versionTwo(Path file) {
        Files.readString(file).replaceFirst(/"version"\s*:\s*1/, '"version":2')
    }

    /** Builds a branch of the named shape the way the factory, or damage to it, leaves one. */
    void branchOfShape(String shape, String taskId) {
        def implement = TaskState.atStageStart('implement')
        switch (shape) {
                    case 'Bare' -> runner.run(cloneDir, 'branch', 'gnomish/' + taskId)
                    case 'Created' -> create(taskId, implement)
                    case 'Answered' -> create(taskId, implement, [
                        new Decision('go on', 'implement', 'op', Instant.EPOCH)
                    ])
                    case 'AwaitingApproval' -> create(taskId, new TaskState(new Position.AwaitingApproval('implement'), 0, [], ExecutorUsage.none()))
                    case 'InProgress' -> withRound(taskId)
                    case 'Parked' -> finished(taskId, new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'implement'))
                    case 'CompletedUncleaned' -> finished(taskId, new TaskOutcome.Completed(implement))
                    case 'Delivered' -> finished(taskId, new TaskOutcome.Completed(implement), true)
                    case 'Corrupt' -> damaged(taskId) { Path dir ->
                        Files.writeString(dir.resolve('state.json'), '{ not json')
                    }
                    case 'Unknown' -> damaged(taskId) { Path dir ->
                        Files.delete(dir.resolve('task.json'))
                    }
                    case 'UnsupportedVersion' -> damaged(taskId) { Path dir ->
                        Files.writeString(dir.resolve('state.json'), versionTwo(dir.resolve('state.json')))
                    }
                    default -> throw new IllegalArgumentException(shape)
                }
    }
}
