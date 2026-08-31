package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState

/**
 * Task creation as a kill-point table row (FR7, design D13 of harden-task-branch-contract): the
 * STARTED commit is the durable intent, and the first push to {@code origin} is what makes it
 * exist for anyone else.
 *
 * <p>The row this file adds is the one the harness was missing. Park, finish and decision all
 * begin from a task that has already been created and published — creation itself was a premise,
 * so its own kill windows were never enumerated, even though FR7 singles the first push out as the
 * only load-bearing push in the factory and task 6.2 gives it a bounded retry and an
 * abort-before-round on exhaustion. Those rules exist because of exactly the window below.
 *
 * <p>Its two windows, classified where the fleet looks:
 *
 * <ul>
 *   <li><b>After the STARTED commit</b> — {@code origin} answers {@code Bare}. The work exists on
 *       one instance's disk and nowhere else; no other instance can find it, and the heartbeat's
 *       recovery cannot converge a branch that exists nowhere but that disk. The recovery owner
 *       the shape table names for {@code Bare} is take routing, and take routing is what runs.
 *   <li><b>After the first push</b> — {@code origin} answers {@code Created}, and the transition
 *       is settled.
 * </ul>
 *
 * <p>The pickup is a <em>second</em> instance's {@code createTask}, over its own clone: the first
 * instance is gone and so is its unpushed branch, which is the whole point of the window. It runs
 * only while {@code origin} is still {@code Bare}, so a second pass over a published branch does
 * nothing — the idempotence the harness asserts.
 *
 * <p>One deviation, stated rather than hidden: step 1 delivers the branch ref directly instead of
 * through {@code FirstPush}, which is package-private to {@code adapter.git}. The steps only need
 * to land the durable effects the windows sit between; the production code this row exercises is
 * the pickup, which goes through {@code PushBestEffortTaskRepository.createTask} — {@code
 * FirstPush} included.
 */
final class CreationKillPoints {

    private CreationKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param world builds a fresh {@link CreationWorld}: an origin, a dying instance's clone, and
     *     a recovering instance's push-decorated repository
     */
    static KillPointTransition transition(String medium, Closure world) {
        new KillPointTransition(
                name: "${medium} task creation",
                steps: [
                    'the STARTED commit on the creating instance',
                    'the first push to origin',
                ],
                world: world,
                step: { CreationWorld w, int index -> step(w, index) },
                shape: { CreationWorld w -> w.shape() },
                pickup: { CreationWorld w -> pickup(w) },
                fingerprint: { CreationWorld w -> w.fingerprint() },
                frozenShapes: ['Bare', 'Created'],
                converged: 'Created')
    }

    private static void step(CreationWorld world, int index) {
        if (index == 0) {
            world.creating.createTask(
                    new TaskContext(world.taskId, 'title', 'body', []), null, TaskState.atStageStart('build'))
        } else {
            world.gitOutput(world.creatingClone, 'push', 'origin',
                    "refs/heads/${world.branch()}:refs/heads/${world.branch()}")
        }
    }

    /**
     * Take routing's answer to a {@code Bare} branch: create it. Run by an instance that never saw
     * the first one's clone, so it publishes its own STARTED commit — and skipped entirely once
     * {@code origin} carries the branch, which is what makes a second pass a no-op.
     */
    private static void pickup(CreationWorld world) {
        if (world.published()) {
            return
        }
        world.recovering.createTask(
                new TaskContext(world.taskId, 'title', 'body', []), null, TaskState.atStageStart('build'))
    }
}
