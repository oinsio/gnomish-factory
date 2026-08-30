package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.PushBestEffortTaskRepository
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the two branch media a kill point can freeze — the host factory clone with worktrees, and
 * the container's bare-object repository — each seeded to the same starting state: a created task,
 * claimed by this instance on an in-memory tracker.
 *
 * <p>Every kill point gets its own freshly built world (its own temp subdirectory), so one window's
 * repair never seeds the next window's premise.
 */
trait KillPointWorlds implements BareGitRepoFixture {

    static final String TASK_ID = 'PROJ-1'

    /** The host medium: a real working clone, worktrees, {@link GitTaskRepository}. */
    KillPointWorld hostWorld(Path root) {
        Path clone = initWorkingRepo(root, 'my-project')
        Files.writeString(clone.resolve('instructions.md'), 'build it\n')
        commitAll(clone, 'init')
        def store = new GitTaskRepository(
                new GitProcessRunner(), clone, root.resolve('worktrees-root'), ClaimEpochSource.NONE)
        seed(clone, store, null)
    }

    /** The container medium: a real bare repo written through {@link GitObjectsTaskRepository}. */
    KillPointWorld containerWorld(Path root) {
        Path work = initWorkingRepo(root, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')
        Path bare = initBareRepo(root, 'origin.git')
        addRemote(work, 'origin', bare.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        Path index = root.resolve('index')
        Files.createDirectories(index)
        seed(bare, new GitObjectsTaskRepository(GitObjects.open(bare, index), ClaimEpochSource.NONE), 'base')
    }

    /**
     * The creation transition's world: a bare {@code origin} with a base commit, the clone of the
     * instance that dies mid-creation, and a second instance's clone whose push-decorated
     * repository is the pickup. No task is created here — creating it IS the transition.
     */
    CreationWorld creationWorld(Path root) {
        Path origin = initBareRepo(root, 'origin.git')
        Path creating = initWorkingRepo(root, 'creating-clone')
        Files.writeString(creating.resolve('instructions.md'), 'build it\n')
        commitAll(creating, 'init')
        addRemote(creating, 'origin', origin.toString())
        gitOutput(creating, 'push', 'origin', 'HEAD:refs/heads/base')
        // A bare repo's HEAD still points at the default branch name it was initialized with, which
        // nothing here ever created; without this the recovering clone checks out nothing and its
        // own createTask cannot resolve a base.
        gitOutput(origin, 'symbolic-ref', 'HEAD', 'refs/heads/base')

        Path recovering = root.resolve('recovering-clone')
        gitOutput(root, 'clone', origin.toString(), recovering.toString())

        def runner = new GitProcessRunner()
        new CreationWorld(
                origin: origin,
                creatingClone: creating,
                creating: new GitTaskRepository(
                        runner, creating, root.resolve('creating-worktrees'), ClaimEpochSource.NONE),
                recovering: new PushBestEffortTaskRepository(
                        new GitTaskRepository(
                                runner, recovering, root.resolve('recovering-worktrees'), ClaimEpochSource.NONE),
                        runner,
                        recovering),
                taskId: TASK_ID)
    }

    private KillPointWorld seed(Path repoDir, TaskLifecycleStore store, String baseRef) {
        def tracker = new InMemoryTracker()
        def trackerHarness = new InMemoryTrackerHarness(tracker)
        def instanceId = new InstanceId('gnomish-factory', 'kp0001')
        def ref = new TaskRef(TASK_ID)
        trackerHarness.seedWorkingWithClaim(tracker, ref, instanceId.value())
        store.createTask(new TaskContext(TASK_ID, 'title', 'body', []), baseRef, TaskState.atStageStart('build'))
        new KillPointWorld(
                repoDir: repoDir,
                store: store,
                taskId: TASK_ID,
                ref: ref,
                instanceId: instanceId,
                tracker: tracker,
                trackerHarness: trackerHarness)
    }
}
