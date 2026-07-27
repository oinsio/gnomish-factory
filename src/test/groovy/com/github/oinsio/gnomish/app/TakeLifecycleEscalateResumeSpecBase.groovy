package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The second M3 lifecycle end to end, driven by two independently constructed {@link TakeCommand}
 * instances against a REAL tracker adapter and a real local git repo: ready -> claim -> escalate
 * -> human decision and return -> resume -> delivered, including resume by a different instance
 * than the one that claimed (M3, NFR-R3).
 *
 * <p>Mirrors {@link TakeLifecycleReadyToDeliveredSpecBase}'s base/subclass split for the same
 * reason: {@code TrackerPortBoundarySpec} (FR1) forbids any class outside {@code adapter.tracker}
 * from depending on a concrete adapter class, and {@link ManualRunAssembly}/{@link TakeCommand}
 * are package-private, so this base lives in {@code app} and only ever touches the tracker
 * through the {@link Tracker} and {@link TrackerAdapterFactory} port types — {@link
 * #seededReadyTrackerAndFactory} and {@link #thread} are the seams a subclass fills in with a
 * concrete adapter (same two seams as the sibling base, task 6.1). Project/pipeline fixture setup
 * and per-instance {@link TakeCommand} construction live in {@link TwoInstanceTakeFixture}
 * (file-size guidance).
 *
 * <p>The pipeline is a single {@code agent-cli} stage with an attempt limit of 1 and a {@code
 * files_exist} check on a file that is never created, so the FIRST attempt fails quality
 * deterministically and the engine escalates with {@code AttemptsExhausted} (see {@code
 * TakeEngineExecutionEscalationSpec} for the same technique).
 *
 * <p>Two entirely separate {@link TakeCommand}/{@link ManualRunAssembly}/{@code FactoryProperties}
 * trios simulate "instance A" and "instance B": no field or object built for instance A is ever
 * reused for instance B (NFR-R3) — only the {@link Tracker} instance (standing in for the shared
 * tracker service, exactly as two real factory processes would share one) and the {@code
 * worktreesRoot}/project directory (standing in for the one machine-local {@code
 * ~/.gnomish/worktrees} convention every factory instance on a box shares, per the
 * git-task-persistence spec) cross between them. Both instances therefore share one {@code
 * worktreesRoot}: git itself (not this test) is what actually enforces that a task branch can be
 * checked out in only one worktree at a time — instance B locates and reuses the SAME worktree
 * instance A already created, purely by reading the branch/state file, never any in-process state
 * instance A held.
 *
 * <p>Implements FR9, FR11, FR12, FR13, M3, NFR-R3 of add-tracker-port.
 */
abstract class TakeLifecycleEscalateResumeSpecBase extends Specification implements TwoInstanceTakeFixture {

    protected static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    /** @return {@code [Tracker, TrackerAdapterFactory]} for one fresh Ready task seeded at {@link #REF} */
    abstract List seededReadyTrackerAndFactory(TaskRef ref, String title, String body)

    /** @return {@code tracker}'s correspondence thread on {@code ref} as {@code "KIND: text"} lines, oldest first */
    abstract List<String> thread(Tracker tracker, TaskRef ref)

    /** Simulates the human side of the escalation: reply, then move the parked task back to Ready. */
    abstract void replyAndReturnToReady(TaskRef ref, String replyText)

    def setup() {
        def seeded = seededReadyTrackerAndFactory(REF, 'Add widgets', 'please add widgets')
        tracker = seeded[0] as Tracker
        trackerFactory = seeded[1] as TrackerAdapterFactory
        writeTwoInstanceProjectFixture()
    }

    def "ready -> claim -> escalate -> human reply and return -> resume by a different instance -> delivered"() {
        given: 'instance A claims the seeded Ready task via its own, independently built TakeCommand'
        def instanceA = newCommand('instance-a')

        when: 'instance A takes the ref; the single attempt fails quality and the run escalates'
        instanceA.run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'the run parks the task awaiting a human (AttemptsExhausted -> ESCALATION), exit code 10 (D16)'
        def firstRun = thrown(TakeExitCodeException)
        firstRun.exitCode() == 10

        and: 'the tracker itself now reports AwaitingHuman'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.AwaitingHuman

        when: 'a human replies with a decision and moves the task back to ready (D12)'
        replyAndReturnToReady(REF, 'please retry, I fixed the environment')

        and: 'the environment fix lands in the shared worktree itself (not via any in-process state)'
        fixMissingFileInSharedWorktree()

        and: 'instance B — a second, freshly built TakeCommand sharing nothing in-process with instance A — takes the same ref'
        def instanceB = newCommand('instance-b')
        instanceB.run(takeArgs('take', 'PROJ-1', "--dir=$projectDir"))

        then: 'instance B resumed, acknowledged the decision, and delivered the task (exit code 0)'
        def secondRun = thrown(TakeExitCodeException)
        secondRun.exitCode() == 0

        and: 'the tracker ends Finished'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'the tracker thread alone tells the full cross-instance story, in order (UX4, NFR-R3)'
        def entries = thread(tracker, REF)
        entries.size() == 5
        entries[0].startsWith('CLAIM:')
        entries[0].contains('instance-a')
        entries[1].startsWith('PARK:')
        entries[2].startsWith('CLAIM:')
        entries[2].contains('instance-b')
        entries[3].startsWith('ACK:')
        entries[3].contains('please retry, I fixed the environment')
        entries[4].startsWith('FINISH:')
    }

    /**
     * Instance B's retry must actually pass so the run can reach {@code Delivered}: writes the
     * file the {@code build} stage's {@code files_exist} check requires directly into the task's
     * worktree — the same deterministic path ({@code <worktreesRoot>/project/PROJ-1}, {@code
     * TaskWorktreeManager}'s own resolution) that instance A's worktree already occupies and that
     * instance B's own {@code ensureWorktree} call resolves to and reuses as-is (it does NOT
     * re-check-out from the branch tip on reuse, so a fix committed on the branch from outside
     * that worktree would never surface inside it). This mirrors what "fixing the environment"
     * means for a working-copy-based CLI factory: a human (or another process) edits files in the
     * SAME shared working copy. Not a channel between instance A and B's in-process state
     * (NFR-R3): both instances share only the tracker and the one {@link #worktreesRoot}/project
     * directory convention, exactly as two real factory processes on the same machine would.
     */
    private void fixMissingFileInSharedWorktree() {
        Path worktree = worktreesRoot.resolve('project').resolve('PROJ-1')
        Files.writeString(worktree.resolve('missing-file.txt'), 'now it exists\n')
    }
}
