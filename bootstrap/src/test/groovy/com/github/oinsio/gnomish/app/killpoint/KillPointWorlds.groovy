package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.PushBestEffortTaskRepository
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the two branch media a kill point can freeze — the host factory clone with worktrees, and
 * the container's bare-object repository — each seeded to the same starting state: a created task,
 * claimed by this instance on an in-memory tracker.
 *
 * <p>Every kill point gets its own freshly built world (its own temp subdirectory), so one window's
 * repair never seeds the next window's premise.
 *
 * <p>Each world builds its own {@link ClaimEpochBook} and hands it to the lifecycle writer it
 * creates — one record per simulated instance, never a claimless source (FR5, design D3 of
 * fix-claim-epoch-fence). The creation world therefore holds two, because it simulates two
 * instances: the one that dies mid-creation and the one that picks the task up. A world that
 * records no claim leaves its book unfilled, and its commits carry no epoch trailer — the same
 * shape the plain {@code gnomish run} path has in production.
 */
trait KillPointWorlds implements BareGitRepoFixture {

    static final String TASK_ID = 'PROJ-1'

    /**
     * The host medium: a real working clone, worktrees, {@link GitTaskRepository}.
     *
     * <p>Its runner is the recording git stand-in, so a row can assert what a pickup SPENDS on
     * reads of the worktree's own {@code HEAD} — the medium the host readers moved to — and not
     * only what it leaves behind (NFR-P1 of fix-envelope-medium). The container medium reads
     * through bare objects in-process and has no such cost to bound.
     */
    KillPointWorld hostWorld(Path root) {
        Path clone = initGnomishClone(root, 'my-project')
        Path worktreesRoot = root.resolve('worktrees-root')
        def epochs = new ClaimEpochBook()
        Path gitLog = root.resolve('git-invocations.log')
        def runner = new GitProcessRunner(recordingGit(gitLog).toString())
        def store = new GitTaskRepository(runner, clone, worktreesRoot, epochs)
        def world = seed(clone, store, epochs, 'HEAD', worktreesRoot.resolve('my-project').resolve(TASK_ID))
        world.gitLog = gitLog
        world.runner = runner
        world
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
        def epochs = new ClaimEpochBook()
        seed(bare, new GitObjectsTaskRepository(GitObjects.open(bare, index), epochs, cursors), epochs, 'base', null)
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
                        runner, creating, root.resolve('creating-worktrees'), new ClaimEpochBook()),
                recovering: new PushBestEffortTaskRepository(
                        new GitTaskRepository(
                                runner, recovering, root.resolve('recovering-worktrees'), new ClaimEpochBook()),
                        runner,
                        recovering),
                recoveringClone: recovering,
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
                ref, new TaskSnapshot(TASK_ID, UntrustedText.tracker('title'), UntrustedText.tracker('body')), new TrackerTaskState.Ready(), AbortFacts.none())

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

    /**
     * The outage row's world: the same claim world, with the claimant's {@code origin} URL pointed
     * at a path no repository sits at — a remote that cannot answer, which is the condition NFR-R3
     * is about. The bare {@code origin} itself stays on disk, because the branch medium's emptiness
     * is still what the row asserts, and a deleted repository would make that assertion vacuous.
     */
    ClaimWorld claimOutageWorld(Path root) {
        def world = claimWorld(root)
        gitOutput(world.claimantClone, 'remote', 'set-url', 'origin', root.resolve('no-such-origin.git').toString())
        world.baseRefGit = newGitBaseRefs()
        world
    }

    /** A working clone with a minimal {@code .gnomish/} committed, ready for {@code createTask}. */
    private Path initGnomishClone(Path root, String name) {
        Path clone = initWorkingRepo(root, name)
        Files.createDirectories(clone.resolve('.gnomish'))
        Files.writeString(clone.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(clone, 'init')
        clone
    }

    private KillPointWorld seed(
            Path repoDir, TaskLifecycleStore store, ClaimEpochBook epochs, String baseRef, Path worktree) {
        def tracker = new InMemoryTracker()
        def trackerHarness = new InMemoryTrackerHarness(tracker)
        def instanceId = new InstanceId('gnomish-factory', 'kp0001')
        def ref = new TaskRef(TASK_ID)
        trackerHarness.seedWorkingWithClaim(tracker, ref, instanceId.value())
        // The seeded claim's epoch goes into the world's own record BEFORE the first write, exactly
        // as EpochRecordingTracker fills it at a live claim — so every commit this world lands
        // carries the tenure that wrote it, and a reclaim row is reading a genuinely stamped tip
        // rather than an unstamped one that would pass for the wrong reason (FR6 of
        // fix-claim-epoch-fence, `testing.md`: adversarial fixtures).
        epochs.issued(TASK_ID, tracker.listOpen().find {
            it.ref() == ref
        }.facts().claim().liveVersion().epoch())
        store.createTask(new TaskContext(TASK_ID, UntrustedText.tracker('title'), UntrustedText.tracker('body'), []), TaskStart.commit(repoDir, baseRef), TaskStart.pin(baseRef, BaseRule.EXPLICIT_ARGUMENT), TaskState.atStageStart('build'))
        new KillPointWorld(
                repoDir: repoDir,
                store: store,
                epochs: epochs,
                worktree: worktree,
                taskId: TASK_ID,
                ref: ref,
                instanceId: instanceId,
                tracker: tracker,
                trackerHarness: trackerHarness)
    }
}
