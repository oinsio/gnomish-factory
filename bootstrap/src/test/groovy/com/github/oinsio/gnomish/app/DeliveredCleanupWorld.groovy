package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * One project of the delivered-cleanup identity specs (FR4, NFR-R1 of
 * fix-terminal-receipt-convergence): a registered factory clone with a real bare {@code origin} in
 * the adversarial default posture ({@link BareGitRepoFixture#addOrigin}), a real in-memory tracker,
 * the production {@link TaskGit} bundle — whose lifecycle store pushes every closing commit to
 * {@code origin}, so "origin carries the cleanup" is observed, not assumed — and the execution
 * medium ({@link DeliveredCleanupMedium}).
 *
 * <p>A {@code CompletedUncleaned} tip is seeded through the medium's own production store. Nothing
 * here cleans; the consumers under test do ({@link DeliveredCleanupEntries}).
 */
final class DeliveredCleanupWorld implements BareGitRepoFixture, AppAssemblyFixture {

    static final String TASK_ID = 'PROJ-1'
    static final TaskRef REF = new TaskRef(TASK_ID)

    final Path projectDir
    final Path origin
    final RegisteredClone clone
    /** The process's one bundle: every command of this world drives it, as the root hands its one bean. */
    final TaskGit git
    final DeliveredCleanupMedium medium
    /** The one tracker of the project: a second clone's instance shares it with the first's. */
    final InMemoryTracker tracker
    final InMemoryTrackerHarness harness

    DeliveredCleanupWorld(Path root, String medium) {
        git = TaskGitFixture.real()
        tracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(tracker)
        this.medium = new DeliveredCleanupMedium(medium, git, root.resolve('guard'))
        projectDir = initWorkingRepo(root, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'),
                'purpose: build it\nexecutor:\n  type: agent-cli\n  model: model-x\n'
                + 'instructions: stages/build/instructions.md\nadvancement: auto\n')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'),
                'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n  github:\n'
                + '    api-url: https://api.github.com\n    repo: acme/widgets\n')
        commitAll(projectDir)
        origin = addOrigin(projectDir, root)
        clone = RegisteredCloneFixture.registered(root.resolve('home'), projectDir)
    }

    /**
     * Another instance of the same project: its own factory home and its own clone of {@code
     * first}'s {@code origin}, its own {@link TaskGit} bundle, the one tracker shared — two
     * processes on two machines, meeting only at {@code origin} and the tracker.
     */
    private DeliveredCleanupWorld(DeliveredCleanupWorld first, Path root) {
        // A later clock than the first's: two cleanup commits over one tip must be two objects.
        git = TaskGitFixture.realAt(new VirtualClock(Instant.EPOCH + Duration.ofHours(1)))
        tracker = first.tracker
        harness = first.harness
        medium = new DeliveredCleanupMedium(first.medium.name, git, root.resolve('guard'))
        origin = first.origin
        projectDir = seedClone(root, origin.toString(), root.resolve('project'))
        clone = RegisteredCloneFixture.registered(root.resolve('home'), projectDir)
    }

    /** A second instance's world over its own clone of this world's {@code origin} (design D12). */
    DeliveredCleanupWorld secondClone(Path root) {
        new DeliveredCleanupWorld(this, Files.createDirectories(root))
    }

    /** Seeds a task branch whose tip records {@code Completed} and still carries its envelope. */
    void completedUncleaned(String taskId = TASK_ID) {
        String base = currentBranch(projectDir)
        TaskLifecycleStore store = medium.store(clone, taskId)
        store.createTask(context(taskId), TaskStart.commit(projectDir, base),
                TaskStart.pin(base, BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        store.recordOutcome(taskId, new TaskOutcome.Completed(TaskState.atStageStart('build')), TrackerWrite.OWED)
        assert shape(taskId) == 'CompletedUncleaned'
        assert !medium.workspaceGone(clone, taskId): 'the seeded workspace must exist for its removal to mean anything'
    }

    /** A Ready task on the tracker, never claimed, never finished. */
    void trackerReady(TaskRef ref = REF) {
        harness.seed(ref, new TaskSnapshot(ref.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
    }

    /** A task another instance claimed and finished: the finish landed, its cleanup did not. */
    void trackerFinished() {
        trackerReady()
        harness.seedWorkingWithClaim(tracker, REF, 'gnomish-factory-elsewhere')
        tracker.finish(REF, 'all stages passed')
    }

    /** A finished task a human moved back to Ready. */
    void trackerReopened() {
        trackerFinished()
        harness.reopenFinished(REF)
    }

    String shape(String taskId = TASK_ID) {
        git.branches().classifyShape(projectDir, taskId).class.simpleName
    }

    /**
     * The shape of the branch at the remote-tracking ref the clone already holds, read without a
     * lookup: {@link #shape} locates first, and a locate of a branch with no local head runs the
     * touchpoint fetch, which lands the register and would erase the posture it is asked about.
     */
    String shapeAtHeldTracking(String taskId = TASK_ID) {
        git.branches().classifyShape(projectDir, taskId, new BranchLocation.RemoteTracking(trackingRef(taskId)))
                .class.simpleName
    }

    boolean workspaceGone() {
        medium.workspaceGone(clone, TASK_ID)
    }

    /** The cleanup is at origin: origin's branch and register name the clone's cleaned tip. */
    boolean originCarriesCleanup() {
        String tip = gitOutput(projectDir, 'rev-parse', branchRef())
        gitOutput(origin, 'rev-parse', branchRef()) == tip
                && gitOutput(origin, 'rev-parse', registerRef()) == tip
                && gitOutput(origin, 'ls-tree', branchRef(), '--', '.gnomish-task') == ''
    }

    /** Every durable thing a run could write: both refs in the clone and at origin, the tracker's thread. */
    Map<String, Object> footprint() {
        [tip: gitOutput(projectDir, 'rev-parse', branchRef()), register: gitOutput(projectDir, 'rev-parse', registerRef()),
            originTip: gitOutput(origin, 'rev-parse', branchRef()), originRegister: gitOutput(origin, 'rev-parse', registerRef()),
            thread: harness.threadAsStrings(REF)]
    }

    /** The task's CLAIM entries on the tracker thread. */
    int claims() {
        harness.threadAsStrings(REF).count { it.startsWith('CLAIM') } as int
    }

    /** Commit subjects of the task branch, oldest first. */
    List<String> subjects() {
        gitOutput(projectDir, 'log', '--reverse', '--format=%s', branchRef()).readLines()
    }

    static String branchRef(String taskId = TASK_ID) {
        'refs/heads/' + TaskIdSanitizer.branchName(taskId)
    }

    /** The clone holds a local head for the task's branch. */
    boolean holdsHead(String taskId = TASK_ID) {
        gitOutput(projectDir, 'for-each-ref', branchRef(taskId)) != ''
    }

    /** The clone holds a remote-tracking ref for the task's branch. */
    boolean holdsTracking(String taskId = TASK_ID) {
        gitOutput(projectDir, 'for-each-ref', trackingRef(taskId)) != ''
    }

    /** The clone holds the task's factory register. */
    boolean holdsRegister(String taskId = TASK_ID) {
        gitOutput(projectDir, 'for-each-ref', registerRef(taskId)) != ''
    }

    /** The task branch's remote-tracking ref: what a second clone holds of a branch it never wrote. */
    static String trackingRef(String taskId = TASK_ID) {
        'refs/remotes/origin/' + TaskIdSanitizer.branchName(taskId)
    }

    static String registerRef(String taskId = TASK_ID) {
        "refs/gnomish-factory/${taskId}"
    }
}
