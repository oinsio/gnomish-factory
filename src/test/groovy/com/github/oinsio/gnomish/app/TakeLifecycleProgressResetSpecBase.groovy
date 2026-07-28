package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.BackoffPolicy
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The "Progress resets the counter" lifecycle end to end (fix-abort-progress-reset FR3, NFR-C1,
 * M1): two aborts recorded directly against a fresh {@code Ready} task, a reclaim and one durable
 * round driven through the REAL {@code take} engine (proving {@link
 * com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence}'s round-boundary {@code
 * recordProgress} wiring, not just the port method in isolation — task 4.1-4.3 already cover that
 * unit), then one more abort. {@link com.github.oinsio.gnomish.app.port.tracker.AbortFacts#count()}
 * afterward is one — only the abort after the durable round — not three, which is exactly the
 * count a backoff/fuse decision reads.
 *
 * <p>Mirrors the {@link TakeLifecycleAbortSpecBase}/{@link TakeLifecycleRevocationSpecBase}
 * base/subclass split: {@code TrackerPortBoundarySpec} (FR1 of add-tracker-port) forbids any class
 * outside {@code adapter.tracker} from depending on a concrete adapter class, so this base lives in
 * {@code app} and only ever touches the tracker through the {@link Tracker}/{@link
 * TrackerAdapterFactory} port types — {@link #seededReadyTrackerAndFactory} and {@link #thread} are
 * the seams a subclass fills in with a concrete adapter. Split into its own file (not folded into
 * {@link TakeLifecycleAbortSpecBase}) because that base and its {@code InMemoryTakeLifecycleAbortSpec}
 * subclass already sit near the file-size guidance cap (`.claude/rules/process-invariants.md`).
 *
 * <p>Unlike {@link TakeLifecycleAbortSpecBase}, the two aborts here and the final abort are
 * recorded directly via {@link Tracker#recordAbort} — a genuine port-level abort marker, just not
 * routed through a forced engine failure — since the scenario under test is the counter's reset
 * boundary, not how an abort is produced; only the middle "durable round" step must run through the
 * real engine, which is what proves the wiring.
 *
 * <p>Implements FR3, NFR-C1, M1 of fix-abort-progress-reset.
 */
abstract class TakeLifecycleProgressResetSpecBase extends Specification implements AbortLifecycleFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Instant START = Instant.parse('2026-01-01T00:00:00Z')
    private static final String INSTANCE = 'gnomish-aaaaaa'

    @TempDir
    Path tempDir

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        writeAbortProjectFixture()
    }

    def "Progress resets the counter"() {
        given: 'two aborts recorded directly against the fresh Ready task, before any progress'
        tracker.recordAbort(REF, new AbortRecord('boom-1', INSTANCE, START))
        tracker.recordAbort(REF, new AbortRecord('boom-2', INSTANCE, START.plusSeconds(1)))
        tracker.fetchTask(REF).abortFacts().count() == 2

        when: 'the task is reclaimed and driven through one durable round via the real take engine'
        def roundTime = START.plusSeconds(120)
        newCommand(roundTime).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the run reaches the Delivered exit code (0), per design D16'
        def delivered = thrown(TakeExitCodeException)
        delivered.exitCode() == 0

        and: 'the round-boundary hook has already reset the tally (FR2, FR3)'
        def afterProgress = tracker.fetchTask(REF)
        afterProgress.state() instanceof TrackerTaskState.Finished
        afterProgress.abortFacts().count() == 0

        when: 'one more abort happens after that durable progress'
        tracker.recordAbort(REF, new AbortRecord('boom-3', INSTANCE, roundTime.plusSeconds(1)))

        then: 'the count reflects only the abort since progress — one, not three (FR3, M1)'
        def afterFinalAbort = tracker.fetchTask(REF)
        afterFinalAbort.abortFacts().count() == 1

        and: 'a backoff/fuse decision reading this count sees the base delay, not the stale count=3 doubled delay (NFR-C1)'
        BackoffPolicy.delay(afterFinalAbort.abortFacts().count(), BACKOFF_BASE, BACKOFF_CAP) == BACKOFF_BASE
        BackoffPolicy.delay(afterFinalAbort.abortFacts().count(), BACKOFF_BASE, BACKOFF_CAP) !=
                BackoffPolicy.delay(3, BACKOFF_BASE, BACKOFF_CAP)

        and: 'the thread confirms only one ABORT entry follows the PROGRESS marker'
        def entries = thread(tracker, REF)
        def progressIndex = entries.findIndexOf { it.startsWith('PROGRESS:') }
        progressIndex >= 0
        entries.drop(progressIndex + 1).count { it.startsWith('ABORT:') } == 1
    }
}
