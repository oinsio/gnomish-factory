package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The infrastructure-abort lifecycle end to end (task 6.4 of add-tracker-port, FR10, FR14): a
 * genuine engine {@code Aborted} outcome, below the fuse, returns the task to {@code Ready} with a
 * correspondence {@code ABORT} entry and an {@link com.github.oinsio.gnomish.app.port.tracker.AbortFacts}
 * count of one (FR14); immediately after, a BARE auto {@code take} run sees the task hidden from
 * the eligible queue while its exponential backoff window has not expired (FR10, D10), then sees
 * and re-claims it once the clock advances past that window. Explicit {@code take <ref>} then
 * repeats the abort — ignoring backoff (mandate, D10) — until the consecutive count reaches the
 * configured fuse threshold K, at which point the task is parked {@code AwaitingHuman(INFRA)} with
 * a report narrating the abort history (count/threshold/cause) instead of returning to {@code
 * Ready} (FR14, D16, exit code 13); a further explicit attempt is then refused rather than
 * re-attempted (FR9, NFR-C1).
 *
 * <p>Mirrors the {@link TakeLifecycleReadyToDeliveredSpecBase}/{@link
 * TakeLifecycleRevocationSpecBase} base/subclass split: {@code TrackerPortBoundarySpec} (FR1)
 * forbids any class outside {@code adapter.tracker} from depending on a concrete adapter class,
 * and {@link ManualRunAssembly}/{@link TakeCommand}/{@link TakeBareAuto} are package-private, so
 * this base lives in {@code app} and only ever touches the tracker through the {@link Tracker}/
 * {@link TrackerAdapterFactory} port types — {@link #seededReadyTrackerAndFactory}, {@link
 * #thread}, and {@link #armToAbortOnNextRoundBoundaryCheck} are the seams a subclass fills in with
 * a concrete adapter and a concrete abort-forcing mechanism. Project/pipeline fixture setup and
 * per-invocation {@link TakeCommand} construction live in {@link AbortLifecycleFixture} (file-size
 * guidance).
 *
 * <p>The pipeline is a single {@code agent-cli} {@code AUTO} stage with no {@code verify} checks
 * (trivially passing on its first and only attempt), backed by the fake agent's {@code
 * plain-round} scenario (the 6.1/6.3 technique): the round completes and persists normally, so an
 * abort here can only come from the round-boundary "still ours and alive" tracker check inside
 * {@link com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence#persist} — {@link
 * #armToAbortOnNextRoundBoundaryCheck} arms the tracker so that check throws a plain {@code
 * RuntimeException} (an infrastructure failure, not a revocation) instead of answering, which
 * {@code AttemptJournal#commit} (add-stage-engine) turns into a genuine {@code
 * TaskOutcome.Aborted} per its documented contract — see {@link
 * com.github.oinsio.gnomish.app.TakeEngineExecution} for how that outcome is then routed to {@link
 * com.github.oinsio.gnomish.app.take.AbortHandler} rather than the ordinary outcome mapper.
 *
 * <p>The fuse threshold K is configured to {@link AbortLifecycleFixture#ABORT_THRESHOLD} (2) so
 * the fuse trips on the SECOND abort, keeping the scenario short without weakening what FR14
 * requires: an arbitrary K only changes how many times the cycle repeats, not the protocol proved.
 *
 * <p>Implements FR9, FR10, FR14, D10, D16, NFR-C1 of add-tracker-port.
 */
abstract class TakeLifecycleAbortSpecBase extends Specification implements AbortLifecycleFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')
    private static final Instant START = Instant.parse('2026-01-01T00:00:00Z')

    @TempDir
    Path tempDir

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    /**
     * Arms {@code tracker} so the NEXT round-boundary "still ours and alive" check (the second
     * {@code fetchTask} call within one {@code take} run — see class javadoc for the call count)
     * throws a plain {@code RuntimeException} instead of answering, forcing a genuine
     * infrastructure {@code Aborted} outcome; every other {@code fetchTask} call, in this run or
     * any other, answers normally.
     */
    abstract void armToAbortOnNextRoundBoundaryCheck()

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        writeAbortProjectFixture()
    }

    /**
     * Runs {@code command} and returns the resulting exit code, for setup/arrange steps that need
     * a prior run's outcome without a dedicated {@code when/then} block (Spock only allows {@code
     * thrown()} inside {@code then:}).
     */
    private static int exitCodeOf(TakeCommand command, String... rawArgs) {
        try {
            command.run(takeArgs(rawArgs))
            throw new IllegalStateException('take command returned without throwing TakeExitCodeException')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    def "one abort below the fuse returns the task to Ready with an ABORT thread entry and count one"() {
        given: 'the round-boundary check is armed to throw once, forcing a genuine infrastructure abort'
        armToAbortOnNextRoundBoundaryCheck()

        when: 'take is run against the seeded ref in explicit mode'
        newCommand(START).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the run reaches the Aborted exit code (12), below the fuse, per design D16'
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 12

        and: 'the tracker reports the task back in Ready, not parked (FR14)'
        def afterAbort = tracker.fetchTask(REF)
        afterAbort.state() instanceof TrackerTaskState.Ready

        and: 'the abort facts record exactly one abort, timestamped at this run (FR14)'
        afterAbort.abortFacts().count() == 1
        afterAbort.abortFacts().lastAbortAt() == START

        and: 'the tracker thread shows the claim followed by an ABORT entry (UX4)'
        def entries = thread(tracker, REF)
        entries.size() == 2
        entries[0].startsWith('CLAIM:')
        entries[1].startsWith('ABORT:')
    }

    def "backoff hides the aborted task from the bare feed until the window expires, then it is claimable again (FR10, D10)"() {
        given: 'one abort is recorded, per the scenario above'
        armToAbortOnNextRoundBoundaryCheck()
        exitCodeOf(newCommand(START), 'take', 'PROJ-1', "--dir=$projectDir")

        when: 'a BARE take run happens well within the backoff window (count=1 -> delay = base)'
        def withinWindow = (START + BACKOFF_BASE).minusSeconds(1)
        newCommand(withinWindow).run(takeArgs('take', "--dir=$projectDir"))

        then: 'the bare run sees an empty queue — the task is invisible to the auto feed, not re-claimed (FR10)'
        def emptyRun = thrown(TakeExitCodeException)
        emptyRun.exitCode() == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Ready
        thread(tracker, REF).size() == 2

        when: 'a BARE take run happens after the backoff window has expired'
        // No manual branch reset: the branch left behind by the prior abort records outcome
        // Aborted, and TakeDispositionResume resumes it on the return alone (FR9, D12), so the
        // re-claim retries from the last durable state.json position automatically.
        def afterWindow = (START + BACKOFF_BASE).plusSeconds(1)
        newCommand(afterWindow).run(takeArgs('take', "--dir=$projectDir"))

        then: 'the bare run now sees, claims, and delivers the task (FR10)'
        def deliveredRun = thrown(TakeExitCodeException)
        deliveredRun.exitCode() == 0
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished
    }

    def "the K-th abort trips the fuse: AwaitingHuman(INFRA) with a history report, exit 13; further takes are refused (FR14, NFR-C1)"() {
        given: 'the first abort (below threshold), via explicit take which ignores backoff (mandate, D10)'
        armToAbortOnNextRoundBoundaryCheck()
        exitCodeOf(newCommand(START), 'take', 'PROJ-1', "--dir=$projectDir")
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Ready

        when: 'a second explicit take aborts again, reaching the configured threshold (K=2)'
        // No manual branch reset: the take mandate resumes the Aborted branch on the return alone
        // (FR9, D12) and the armed round-boundary check aborts it a second time, accumulating the
        // consecutive count to K without human intervention below the fuse.
        armToAbortOnNextRoundBoundaryCheck()
        def secondAttemptTime = START.plusSeconds(1)
        newCommand(secondAttemptTime).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the fuse trips: exit code 13, parked-infra, per design D16'
        def fuseTrip = thrown(TakeExitCodeException)
        fuseTrip.exitCode() == 13

        and: 'the tracker reports AwaitingHuman(INFRA), not Ready'
        def afterFuse = tracker.fetchTask(REF)
        afterFuse.state() instanceof TrackerTaskState.AwaitingHuman
        (afterFuse.state() as TrackerTaskState.AwaitingHuman).reason().toString() == 'INFRA'

        and: 'the park report narrates the abort history: count reached the configured threshold'
        def entries = thread(tracker, REF)
        def parkEntry = entries.find { it.startsWith('PARK:') }
        parkEntry != null
        parkEntry.contains('2')
        parkEntry.toLowerCase().contains('threshold')

        when: 'a further explicit take is attempted against the now-parked task'
        newCommand(secondAttemptTime.plusSeconds(1)).run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'it is refused rather than re-attempted (FR9, NFR-C1), leaving the tracker state untouched'
        def refused = thrown(TakeExitCodeException)
        refused.exitCode() == 15
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman
    }
}
