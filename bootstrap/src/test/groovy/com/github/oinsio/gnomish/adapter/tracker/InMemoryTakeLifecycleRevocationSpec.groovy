package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TakeLifecycleRevocationSpecBase
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * The concrete {@code InMemoryTracker} instantiation of {@link TakeLifecycleRevocationSpecBase}
 * (task 6.3 of add-tracker-port, FR15): lives inside {@code adapter.tracker} — the one place
 * allowed to name a concrete adapter class alongside the {@code app}-package base spec's port-only
 * seams ({@code Tracker}, {@code TrackerAdapterFactory}) — the same {@code TrackerPortBoundarySpec}
 * (FR1) placement rationale the 6.1/6.2 sibling specs already document.
 *
 * <p>{@link #closeOnSecondFetch} wraps the seeded {@link InMemoryTracker} in {@link
 * CloseOnNthFetchTracker}, the small test-only {@link Tracker} decorator this task adds (also
 * placed here, the only package allowed to depend on {@link InMemoryTracker}/{@link
 * InMemoryTrackerHarness}).
 *
 * <p>Implements FR15 of add-tracker-port.
 */
class InMemoryTakeLifecycleRevocationSpec extends TakeLifecycleRevocationSpecBase {

    private InMemoryTracker realTracker
    private InMemoryTrackerHarness harness

    @Override
    List seededReadyTrackerAndFactory(TaskRef ref, String title, String body) {
        realTracker = new InMemoryTracker()
        harness = new InMemoryTrackerHarness(realTracker)
        harness.seed(ref, new TaskSnapshot(ref.id(), title, body), new TrackerTaskState.Ready(), AbortFacts.none())
        // TrackerAdapterFactory#create returns whatever Tracker `tracker` currently is: the
        // fixture calls seededReadyTrackerAndFactory BEFORE closeOnSecondFetch, so this closure
        // captures the mutable `tracker` field by reference and picks up the decorator installed
        // by closeOnSecondFetch below, exactly as TakeCommand resolves a live Tracker at run time.
        def factory = new TrackerAdapterFactory() {
                    String type() {
                        'github'
                    }

                    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture: ref is already canonical')
                    }
                }
        [realTracker, factory]
    }

    @Override
    List<String> thread(Tracker trackerArg, TaskRef ref) {
        harness.threadAsStrings(ref)
    }

    @Override
    void closeOnSecondFetch(TaskRef ref, Path leftoverFile) {
        tracker = new CloseOnNthFetchTracker(realTracker, harness, ref, 2, leftoverFile)
    }
}

/**
 * Test-only {@link Tracker} decorator (task 6.3, FR15): delegates every operation to {@code
 * delegate} unchanged, except that its {@code nth} call to {@link #fetchTask} first writes {@code
 * leftoverFile} into the worktree and closes {@code ref} via {@code harness} — simulating a human
 * closing the tracker task in the same instant the take runner's round-boundary "still ours and
 * alive" check queries {@code fetchTask} (see {@link
 * com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence#persist}, which always
 * calls the real git commit before this check, so anything written here is genuinely uncommitted
 * at the moment revocation is discovered) — before delegating to the now-closed real tracker for
 * the answer.
 *
 * <p>Kept in {@code adapter.tracker} (not {@code adapter.tracker.inmemory}) because it is
 * test-support scaffolding for this one spec, not a reusable in-memory-adapter capability; it may
 * still depend on {@link InMemoryTracker}/{@link InMemoryTrackerHarness} since this package is the
 * one place {@code TrackerPortBoundarySpec} allows that.
 */
class CloseOnNthFetchTracker extends DelegatingTracker {

    private final InMemoryTrackerHarness harness
    private final TaskRef armedRef
    private final int closeOnCallNumber
    private final Path leftoverFile
    private int fetchCount = 0

    CloseOnNthFetchTracker(
    InMemoryTracker delegate, InMemoryTrackerHarness harness, TaskRef armedRef, int closeOnCallNumber,
    Path leftoverFile) {
        super(delegate)
        this.harness = harness
        this.armedRef = armedRef
        this.closeOnCallNumber = closeOnCallNumber
        this.leftoverFile = leftoverFile
    }

    @Override
    TrackerTask fetchTask(TaskRef ref) {
        fetchCount++
        if (ref == armedRef && fetchCount == closeOnCallNumber) {
            Files.writeString(leftoverFile, 'uncommitted work in flight when revocation happened\n')
            harness.close(ref)
        }
        delegate.fetchTask(ref)
    }
}
