package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link WorktreeJanitor#start}/{@link WorktreeJanitor#loop}, task 7.1 of add-factory-serve
 * (design D10, FR14): one tick fires immediately (the startup scan) and every {@link
 * WorktreeJanitor#TICK_INTERVAL} thereafter, driven deterministically by the rendezvous {@link
 * BlockingSleeper} — no real sleeping, no polling. The disposal policy itself is {@link
 * WorktreeJanitorSpec}'s concern; this spec only proves the scheduling cadence.
 *
 * <p>Implements FR14 of add-factory-serve (design D10).
 */
@Timeout(10)
class WorktreeJanitorLifecycleSpec extends Specification {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot
    def ticks = new AtomicInteger()
    def sleeper = new BlockingSleeper()
    def clock = { -> Instant.now() } as Clock

    def setup() {
        cloneDir = Files.createDirectory(tempDir.resolve('my-project'))
        worktreesRoot = tempDir.resolve('worktrees')
    }

    // FR14, D10: the daemon-startup tick runs before any sleep — a fresh instance cleans up
    //     immediately rather than waiting a full hour for its first scan.
    def "ticks once at startup, before the first sleep"() {
        given:
        def disposal = { String key -> ticks.incrementAndGet() } as TaskEnvironmentDisposal
        def janitor = new WorktreeJanitor(worktreesRoot, cloneDir, Duration.ofDays(14), disposal, clock, sleeper, { -> Set.of() })

        when: 'the janitor starts'
        janitor.start()
        def slept = sleeper.awaitEntered()

        then: 'it slept the fixed hourly interval, having already ticked once (a no-op tick here, since no worktrees exist yet)'
        slept == WorktreeJanitor.TICK_INTERVAL
    }

    // FR14, D10: loop() must actually invoke tick(), not merely reach the sleep call — proven here
    //     by an observable effect only tick() produces: an aged, unheld environment gets disposed
    //     of by the time the thread reaches its first sleep, with no tick() call ever made
    //     directly by the test.
    def "the startup tick actually runs and disposes an aged unheld environment"() {
        given: 'a real aged, unheld worktree directory on disk, and a disposal seam that records keys'
        Path projectDir = Files.createDirectories(worktreesRoot.resolve('my-project').resolve('task-aged'))
        Files.setLastModifiedTime(projectDir, FileTime.from(Instant.now() - Duration.ofDays(30)))
        List<String> disposedKeys = Collections.synchronizedList(new ArrayList<String>())
        def disposal = { String key -> disposedKeys << key } as TaskEnvironmentDisposal
        def janitor = new WorktreeJanitor(worktreesRoot, cloneDir, Duration.ofDays(14), disposal, clock, sleeper, { -> Set.of() })

        when: 'the janitor starts and reaches its first sleep'
        janitor.start()
        sleeper.awaitEntered()

        then: 'tick() ran before that sleep and already disposed of the aged environment'
        disposedKeys == ['task-aged']
    }

    // FR14, D10: after the interval elapses, the janitor ticks again — the hourly cadence.
    def "ticks again after the hourly interval elapses"() {
        given:
        def disposal = { String key -> ticks.incrementAndGet() } as TaskEnvironmentDisposal
        def janitor = new WorktreeJanitor(worktreesRoot, cloneDir, Duration.ofDays(14), disposal, clock, sleeper, { -> Set.of() })
        janitor.start()
        sleeper.awaitEntered()

        when: 'two intervals elapse'
        sleeper.releaseOne()
        def secondSleep = sleeper.awaitEntered()
        sleeper.releaseOne()
        def thirdSleep = sleeper.awaitEntered()

        then: 'the loop kept ticking on the same fixed interval, tick after tick'
        secondSleep == WorktreeJanitor.TICK_INTERVAL
        thirdSleep == WorktreeJanitor.TICK_INTERVAL
    }

    // FR14, D10: a tick that throws (e.g. the disposal seam itself fails) does not kill the
    //     janitor thread — the next tick, one interval later, tries again.
    def "a failing tick does not kill the janitor thread"() {
        given: 'one aged, unheld environment whose disposal always throws'
        Files.createDirectories(worktreesRoot.resolve('my-project').resolve('boom'))
        def disposal = { String key -> throw new IllegalStateException('disposal boom') } as TaskEnvironmentDisposal
        def janitor = new WorktreeJanitor(worktreesRoot, cloneDir, Duration.ofSeconds(0), disposal, clock, sleeper, { -> Set.of() })

        when: 'the janitor starts, ticks once (throwing), and reaches the next sleep regardless'
        janitor.start()
        def firstSleep = sleeper.awaitEntered()

        then:
        firstSleep == WorktreeJanitor.TICK_INTERVAL

        when: 'one more interval elapses'
        sleeper.releaseOne()
        def secondSleep = sleeper.awaitEntered()

        then: 'the thread survived the failing tick and looped back around for another'
        secondSleep == WorktreeJanitor.TICK_INTERVAL
    }
}
