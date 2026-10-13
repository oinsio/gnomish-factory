package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.RevocationCheckingAttemptPersistence
import com.github.oinsio.gnomish.app.take.RevocationDetectedException
import com.github.oinsio.gnomish.app.take.RevocationHandler
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.PendingFeature
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR11, D9 of add-factory-serve: SIGTERM while two slots sit between rounds. The stop sequence is
 * the real {@link ServeShutdown}; each slot's next round boundary is the real decorator {@link
 * RevocationCheckingAttemptPersistence} over a real git persistence, and its stop is the real
 * {@link RevocationHandler} over a real {@link InMemoryTracker} and a real origin. The two slot
 * threads stand in only for the take cycle that would call them in that order.
 */
@Timeout(30)
class ServeShutdownGracefulReleaseSpec extends ServeShutdownSpecBase implements BareGitRepoFixture {

    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    private static final TaskState STATE = TaskState.atStageStart('implement')
    private static final TaskRef FIRST = new TaskRef('PROJ-A')
    private static final TaskRef SECOND = new TaskRef('PROJ-B')

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def tracker = new InMemoryTracker()
    def ledger = new SlotLedger(2, new VirtualClock())
    def flag = new ClaimLossFlag()

    /** The claim-stamping bundle production wires, over this spec's runner: the slots draw every writer from it. */
    def taskGit = TaskGitFixture.real(runner)

    /** One task's worktree on its task branch, wired to its own origin. */
    private Path worktreeFor(TaskRef ref) {
        def root = Files.createDirectories(tempDir.resolve(ref.id()))
        def repo = initTaskWorkingRepo(root, ref.id())
        addRemote(repo, 'origin', initBareRepo(root).toString())
        repo
    }

    /** A slot between rounds: its next round boundary meets the flag, and the stop releases the claim. */
    private Runnable slot(TaskRef ref, Path worktree) {
        def persistence = new RevocationCheckingAttemptPersistence(
                taskGit.store().attemptPersistence(worktree, ref.id()), tracker, ref, INSTANCE, flag)
        def handler = new RevocationHandler(tracker, taskGit.worktrees().salvage(worktree), taskGit.branches())
        return {
            while (!flag.isLost(ref)) {
                Thread.sleep(5)
            }
            try {
                persistence.persist(ref.id(), STATE, new ToolTrace(new AttemptKey(ref.id(), 'implement', 1), []))
            } catch (RevocationDetectedException ignored) {
                handler.handle(ref, ref.id(), STATE, worktree, flag.reason(ref))
            } finally {
                ledger.release(ref)
            }
        } as Runnable
    }

    // Scenario: serve/factory-serve / Graceful release
    @PendingFeature(reason = 'F97 (add-claim-return): the stop path ends in Tracker#release, which drops the claim and leaves the task Working; nothing returns it to Ready before the TTL')
    def "FR11: SIGTERM with two slots between rounds releases both claims within grace, both tasks Ready, rounds on their branches"() {
        given: 'two claimed tasks, each occupying a slot of this instance'
        def harness = new InMemoryTrackerHarness(tracker)
        def slots = [FIRST, SECOND].collect { ref ->
            harness.seedWorkingWithClaim(tracker, ref, INSTANCE.value())
            ledger.acquire()
            ledger.assign(ref)
            def worktree = worktreeFor(ref)
            [ref: ref, worktree: worktree, thread: Thread.ofVirtual().start(slot(ref, worktree))]
        }
        def shutdown = new ServeShutdown(ledger, flag, Duration.ofSeconds(20), new RecordingKiller(), inertLoops())

        when: 'the signal arrives'
        List<ILoggingEvent> events = capture { shutdown.shutdown(null) }
        slots.each { it.thread.join() }

        then: 'both slots drained inside the grace window'
        events.find {
            it.formattedMessage.contains('in-flight task')
        }.formattedMessage.contains('true')
        ledger.freeSlots() == 2

        and: 'both tasks are Ready at once, with no TTL wait'
        slots.every {
            tracker.fetchTask(it.ref).state() instanceof TrackerTaskState.Ready
        }

        and: "each task branch on origin carries the round committed at the boundary"
        slots.every {
            def branch = "gnomish/${it.ref.id()}"
            gitOutput(it.worktree, 'ls-remote', 'origin', "refs/heads/${branch}").startsWith(currentHead(it.worktree))
        }
    }
}
