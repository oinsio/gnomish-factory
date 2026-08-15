package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The deferred-PARK reconcile proof for the {@code take} run (task 6.5 of add-claim-heartbeat, FR10,
 * D10, NFR-C1), driven by {@link TakeCommand} against a REAL tracker adapter and a real local git
 * repo: a task escalates (branch records {@code Escalated}, the park lands and clears the marker),
 * then the branch is put back into the durable "tracker-write pending" state to model an ORPHANED
 * park — the park write that never confirmed before the holder died, whose stale claim a reaper
 * returned to {@code Ready}. A later {@code take} of the same ref MUST reconcile: re-post the
 * deferred park, end {@code AwaitingHuman}, exit with the park exit code, and run ZERO engine rounds
 * (proven by the reconcile run's thread carrying a {@code PARK} with no preceding round {@code
 * PROGRESS} marker). The symmetric "marker cleared → normal resume runs the engine" case is proven
 * by the sibling {@code InMemoryTakeLifecycleEscalateResumeSpec}, whose human-returned park resumes
 * and delivers.
 *
 * <p>Abstract for the same reason as {@link TakeReconcileLifecycleSpecBase}: a concrete adapter's
 * seeding, thread-reading, and reopen-to-ready all name the concrete adapter type, so they live in a
 * subclass inside {@code adapter.tracker} while this base stays in {@code app} and touches the
 * tracker only through the {@link Tracker} port.
 *
 * <p>Implements FR10, D10, NFR-C1 of add-claim-heartbeat.
 */
abstract class TakeParkReconcileLifecycleSpecBase extends Specification implements TwoInstanceTakeFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    /** Reopens {@code ref} into a fresh {@code Ready} state, discarding its prior correspondence. */
    abstract void reopenAsReady(TaskRef ref, String title, String body)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        writeTwoInstanceProjectFixture()
    }

    def "an orphaned park (Escalated branch + pending marker) reconciles as a deferred park, zero engine rounds"() {
        given: 'instance A takes the ref; the single attempt fails quality and the run escalates and parks'
        def exitA = runExitCode(newCommand('instance-a'), takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        expect: 'the first run parked (ESCALATION, exit 10) and the tracker shows AwaitingHuman'
        exitA == 10
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman

        when: 'the park is modelled as never having confirmed — the branch keeps the tracker-write pending marker'
        markParkPending('PROJ-1')

        and: 'the stale claim was reaped and the task returned to Ready, then take runs again'
        reopenAsReady(REF, 'Add widgets', 'please add widgets')
        def exitB = runExitCode(newCommand('instance-b'), takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the reconcile re-posts the deferred park (ESCALATION, exit 10) and the tracker ends AwaitingHuman'
        exitB == 10
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman

        and: 'the reconcile run ran zero engine rounds: its thread is claim then park, with no round PROGRESS between'
        def entries = thread(tracker, REF)
        entries.size() == 2
        entries[0].startsWith('CLAIM:')
        entries[0].contains('instance-b')
        entries[1].startsWith('PARK:')
        entries.every { !it.startsWith('PROGRESS:') }

        and: 'the deferred park confirmed, so the durable tracker-write-pending marker is now cleared'
        pendingMarker('PROJ-1') != Boolean.TRUE
    }

    def "an orphaned PAUSE (Paused branch + pending marker) reconciles as a deferred pause, zero engine rounds"() {
        given: 'instance A takes the ref; the single attempt fails quality and the run escalates and parks'
        def exitA = runExitCode(newCommand('instance-a'), takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        expect: 'the first run parked and the tracker shows AwaitingHuman'
        exitA == 10
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman

        when: 'the branch is re-recorded as a Paused checkpoint whose tracker write never confirmed'
        markPausePending('PROJ-1')

        and: 'the stale claim was reaped and the task returned to Ready, then take runs again'
        reopenAsReady(REF, 'Add widgets', 'please add widgets')
        def exitB = runExitCode(newCommand('instance-b'), takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the reconcile re-posts the deferred pause (CHECKPOINT, exit 11) and the tracker ends AwaitingHuman'
        exitB == 11
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman

        and: 'the reconcile run ran zero engine rounds: its thread is claim then park, with no round PROGRESS between'
        def entries = thread(tracker, REF)
        entries.size() == 2
        entries[0].startsWith('CLAIM:')
        entries[1].startsWith('PARK:')
        entries.every { !it.startsWith('PROGRESS:') }

        and: 'the deferred pause confirmed, so the durable tracker-write-pending marker is now cleared'
        pendingMarker('PROJ-1') != Boolean.TRUE
    }

    /**
     * Re-records an {@code Escalated} outcome on the task branch, which sets the durable
     * "tracker-write pending" marker again — modelling a park whose tracker write never confirmed
     * (the marker the first run's landed park had cleared). The synthetic escalation report only
     * shapes the re-posted park text; {@code deliverPark} reads the real final state from {@code
     * state.json} left by the first run.
     */
    private void markParkPending(String taskId) {
        def repository = new GitTaskRepository(new GitProcessRunner(), projectDir, worktreesRoot)
        repository.recordOutcome(
                taskId, new TaskOutcome.Escalated(TaskState.atStageStart('build'), new EscalationReport.AttemptsExhausted(1)))
    }

    /**
     * Re-records a {@code Paused} checkpoint on the task branch, setting the durable "tracker-write
     * pending" marker again — the pause counterpart of {@link #markParkPending}, modelling a
     * checkpoint whose tracker write never confirmed. {@code deliverPark} reads the real final state
     * from the {@code state.json} left by the first run and re-sends the pause through {@code
     * TakePauseExit}.
     */
    private void markPausePending(String taskId) {
        def repository = new GitTaskRepository(new GitProcessRunner(), projectDir, worktreesRoot)
        repository.recordOutcome(taskId, new TaskOutcome.Paused(TaskState.atStageStart('build'), 'build'))
    }

    /**
     * Reads the branch's durable "tracker-write pending" marker straight from the worktree {@code
     * task.json}, so a test can prove a reconcile-on-resume CLEARED it (confirmed the deferred write)
     * rather than leaving it orphaned. Returns the raw {@code trackerWritePending} flag.
     */
    private Boolean pendingMarker(String taskId) {
        def sanitized = TaskIdSanitizer.sanitize(taskId)
        Files.walk(worktreesRoot).withCloseable { stream ->
            def taskJson = stream
            .filter {
                it.fileName.toString() == 'task.json' && it.toString().contains(sanitized)
            }
            .findFirst()
            .orElseThrow {
                new IllegalStateException("no task.json for ${taskId} under ${worktreesRoot}")
            }
            TaskJsonMapper.readDto(Files.readString(taskJson)).trackerWritePending()
        }
    }

    private static int runExitCode(TakeCommand command, DefaultApplicationArguments appArgs) {
        try {
            command.run(appArgs)
            throw new IllegalStateException('take did not exit with a TakeExitCodeException')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }
}
