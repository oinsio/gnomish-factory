package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.util.concurrent.PollingConditions

/**
 * The daemon-lifetime threads a {@link ServeCommand} starts beside the feed automaton — the
 * worktree janitor and the standing reaper — each proven started by the one effect a
 * fire-and-forget thread has, observed while the automaton itself is only captured, never run.
 *
 * <p>Implements FR14, D10 of add-factory-serve. Implements FR1, FR5 of fix-reaper-idle-liveness.
 */
class ServeBackgroundDutiesSpec extends ServeCommandSpecBase {

    // FR14, D10 (task 5.2): proves ServeCommand#run really calls WorktreeJanitor::start — not
    // merely assembles the janitor — via the only externally observable effect a fire-and-forget
    // janitor thread has: it disposes of a real, aged, unheld worktree shortly after startup. No
    // test seam exists for the janitor (ServeAssembly.worktreeJanitor wires a real ThreadSleeper/
    // SystemClock), so this drives a real `git worktree add`/`git worktree remove --force` round
    // trip and polls for the directory's disappearance.
    def "the worktree janitor is actually started and disposes an aged unheld worktree on startup"() {
        given: 'projectDir is a real git repo with one commit (from setup()), and a registered, aged worktree'
        def worktreePath = worktreesRoot.resolve('project').resolve('aged-task')
        Files.createDirectories(worktreePath.parent)
        addWorktree(projectDir, worktreePath, 'task/aged-task')
        def aged = FileTime.from(Instant.now() - Duration.ofDays(1))
        Files.walk(worktreePath).filter {
            Files.isRegularFile(it)
        }.forEach {
            Files.setLastModifiedTime(it, aged)
        }
        Files.setLastModifiedTime(worktreePath, aged)

        and: 'a config with a tiny worktree-age threshold, so the immediate startup tick disposes it'
        writeConfig(GITHUB_TRACKER_SECTION)
        def command = newCommand(
                [github: fakeFactory(tracker)],
                new CapturingStarter(),
                new ServeProperties(0, null, null, Duration.ofMillis(1), null, null, null, null, null))

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir")) }

        then: 'the janitor thread actually ran its startup tick and removed the aged worktree'
        new PollingConditions(timeout: 5, initialDelay: 0, delay: 0.1).eventually {
            assert !Files.exists(worktreePath)
        }
    }

    // fix-reaper-idle-liveness FR1, FR5: serve starts the standing reaper as its own
    //     daemon-lifetime thread, ticking on its own interval independently of the feed automaton
    //     (never actually driven here — CapturingStarter only captures it). A short
    //     heartbeat-interval makes the reaper's own first tick observable well within the test
    //     timeout: it calls tracker.listOpen() on every tick (Reaper#reapOnce), which nothing else
    //     in this run ever calls (the feed loop that would call listReady/claim never runs), so
    //     any listOpen() call can only be the standing reaper.
    def "starts the standing reaper as a daemon-lifetime thread ticking independently of the feed automaton (fix-reaper-idle-liveness FR1)"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION + '  heartbeat-interval: 20ms\n')
        def listOpenCalls = new AtomicInteger()
        Tracker fakeTracker = [
            listOpen: { listOpenCalls.incrementAndGet(); [] },
        ] as Tracker
        def starter = new CapturingStarter()
        def command = newCommand([github: fakeFactory(fakeTracker)], starter)

        when:
        runsToCompletion { command.run(args('serve', "--dir=$projectDir")) }

        then: 'the automaton was merely captured, never run — nothing but the reaper can call listOpen'
        noExceptionThrown()
        starter.captured != null

        and: 'the standing reaper genuinely ticked on its own thread shortly after startup'
        new PollingConditions(timeout: 5, initialDelay: 0, delay: 0.05).eventually {
            assert listOpenCalls.get() > 0
        }
    }
}
