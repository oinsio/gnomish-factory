package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.PushBestEffortTaskRepository
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.baseref.BaseRule
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
        Files.createDirectories(clone.resolve('.gnomish'))
        Files.writeString(clone.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(clone, 'init')
        def store = new GitTaskRepository(
                new GitProcessRunner(), clone, root.resolve('worktrees-root'), ClaimEpochSource.NONE)
        seed(clone, store, 'HEAD')
    }

    /** The container medium: a real bare repo written through {@link GitObjectsTaskRepository}. */
    KillPointWorld containerWorld(Path root) {
        containerWorld(root, DenialCursorSource.NONE)
    }

    /**
     * The container medium with an environment that answers a denial read position, so a {@code
     * cannotExecute} park mints a committed cursor to preserve (FR5 of
     * fix-denial-attribution-durability). Host mode has no counterpart: it has no egress guard, so
     * no denial source exists to ask (`.claude/rules/manual-sync-pairs.md`).
     */
    KillPointWorld containerWorld(Path root, DenialCursorSource cursors) {
        Path work = initWorkingRepo(root, 'seed-work')
        Files.writeString(work.resolve('a.txt'), 'first')
        commitAll(work, 'init')
        Path bare = initBareRepo(root, 'origin.git')
        addRemote(work, 'origin', bare.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        Path index = root.resolve('index')
        Files.createDirectories(index)
        seed(bare, new GitObjectsTaskRepository(GitObjects.open(bare, index), ClaimEpochSource.NONE, cursors), 'base')
    }

    /**
     * The creation transition's world: a bare {@code origin} with a base commit, the clone of the
     * instance that dies mid-creation, and a second instance's clone whose push-decorated
     * repository is the pickup. No task is created here — creating it IS the transition.
     */
    CreationWorld creationWorld(Path root) {
        Path origin = initBareRepo(root, 'origin.git')
        Path creating = initWorkingRepo(root, 'creating-clone')
        Files.createDirectories(creating.resolve('.gnomish'))
        Files.writeString(creating.resolve('.gnomish/instructions.md'), 'build it\n')
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

    /**
     * The claim transition's world: an empty {@code origin}, the claimant's clone of it, and a
     * Ready task on an in-memory tracker. No task is claimed and no branch is cut here — claiming
     * IS the transition, and the branch that never follows is what the window is about.
     */
    ClaimWorld claimWorld(Path root) {
        Path origin = initBareRepo(root, 'origin.git')
        Path work = initWorkingRepo(root, 'claimant-clone')
        Files.createDirectories(work.resolve('.gnomish'))
        Files.writeString(work.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(work, 'init')
        addRemote(work, 'origin', origin.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')

        def tracker = new InMemoryTracker()
        def ref = new TaskRef(TASK_ID)
        new InMemoryTrackerHarness(tracker).seed(
                ref, new TaskSnapshot(TASK_ID, 'title', 'body'), new TrackerTaskState.Ready(), AbortFacts.none())

        def world = new ClaimWorld(
                origin: origin,
                claimantClone: work,
                taskId: TASK_ID,
                ref: ref,
                instanceId: new InstanceId('gnomish-factory', 'kp0002'),
                tracker: tracker)
        world.armReaper()
        world
    }

    private KillPointWorld seed(Path repoDir, TaskLifecycleStore store, String baseRef) {
        def tracker = new InMemoryTracker()
        def trackerHarness = new InMemoryTrackerHarness(tracker)
        def instanceId = new InstanceId('gnomish-factory', 'kp0001')
        def ref = new TaskRef(TASK_ID)
        trackerHarness.seedWorkingWithClaim(tracker, ref, instanceId.value())
        store.createTask(new TaskContext(TASK_ID, 'title', 'body', []), baseRef, BaseRule.EXPLICIT_ARGUMENT, TaskState.atStageStart('build'))
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
