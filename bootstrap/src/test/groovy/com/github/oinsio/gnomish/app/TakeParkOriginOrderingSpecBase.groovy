package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M2, FR4 of fix-lifecycle-push, end to end against a REAL bare-repo origin and a real {@code take}
 * run: at the moment the tracker's park write is invoked, origin already carries the branch tip that
 * park was recorded on. Where {@code TakeResumeReplicationSpec} pins the ORDER of two port calls,
 * this pins the fact that order exists for — the tracker reads origin from inside its own {@code
 * park}, so the assertion is about the remote's real content at write time, not about a sequence of
 * mock invocations.
 *
 * <p>Abstract for the same reason as {@link TakeParkReconcileLifecycleSpecBase}: seeding a concrete
 * tracker names a concrete adapter class, which belongs in a subclass inside {@code adapter.tracker}
 * while this base stays in {@code app} and touches the tracker only through the {@link Tracker} port.
 */
abstract class TakeParkOriginOrderingSpecBase extends Specification implements TwoInstanceTakeFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')

    private static final String BRANCH = 'gnomish/PROJ-1'

    @TempDir
    Path tempDir

    /** @return a fresh tracker holding one {@code Ready} task at {@link #REF} */
    abstract Tracker seededReadyTracker(TaskRef ref, String title, String body)

    /** @return the adapter factory {@code take} resolves {@code tracker} through */
    abstract TrackerAdapterFactory factoryFor(Tracker tracker)

    protected Path origin

    /** What origin held for the task branch at the instant the park write was invoked. */
    protected String originTipAtParkWrite

    /** What the LOCAL branch held at that same instant — the park commit the write announces. */
    protected String localTipAtParkWrite

    def setup() {
        writeTwoInstanceProjectFixture()
        origin = initBareRepo(tempDir, 'origin.git')
        gitOutput(projectDir, 'remote', 'add', 'origin', origin.toString())
        gitOutput(projectDir, 'push', '-q', 'origin', 'HEAD:refs/heads/main')

        def reading = new OriginReadingTracker(
                seededReadyTracker(REF, 'Add widgets', 'please add widgets'), {
                    originTipAtParkWrite = originTip()
                    localTipAtParkWrite = gitOutput(projectDir, 'rev-parse', "refs/heads/${BRANCH}")
                })
        tracker = reading
        trackerFactory = factoryFor(reading)
    }

    /** What origin currently holds for the task branch, or empty when it has never seen it. */
    protected String originTip() {
        def refs = gitOutput(projectDir, 'ls-remote', 'origin', "refs/heads/${BRANCH}")
        refs.isEmpty() ? '' : refs.split(/\s/)[0]
    }

    def "M2: origin already carries the park's branch tip when the tracker's park write runs"() {
        when: 'a take run whose single attempt fails quality, so the run escalates and parks'
        def exitCode = runExitCode(newCommand('instance-a'), takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the run parked (ESCALATION, exit 10)'
        exitCode == 10
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman

        and: 'the tracker read origin from inside its own park write, and found the park commit there'
        originTipAtParkWrite == localTipAtParkWrite

        and: 'that commit really is the park: origin carries its recorded outcome and pending marker'
        def parked = gitOutput(origin, 'show', "${originTipAtParkWrite}:.gnomish-task/task.json")
        parked.contains('"escalated"')
        parked.contains('"trackerWritePending":true')
    }
}

/**
 * A {@link Tracker} that reads {@code origin} at the instant its park write is invoked, delegating
 * everything else — the "tracker fake reading origin at write time" of task 4.3. A delegating
 * wrapper rather than a Spock spy because the tracker adapters under it are {@code final}.
 */
class OriginReadingTracker implements Tracker {

    @Delegate
    private final Tracker delegate

    private final Closure<?> readOrigin

    OriginReadingTracker(Tracker delegate, Closure<?> readOrigin) {
        this.delegate = delegate
        this.readOrigin = readOrigin
    }

    @Override
    void park(TaskRef ref, ParkReason reason, String report) {
        readOrigin()
        delegate.park(ref, reason, report)
    }
}
