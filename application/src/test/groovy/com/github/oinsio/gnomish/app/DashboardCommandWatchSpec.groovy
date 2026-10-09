package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR9, FR14 of supervise-daemon-loops-and-embed-dashboard (design D10; dashboard-page "Standalone
 * renderer gives up"): {@code gnomish dashboard --watch} starts its {@link DashboardWatch}'s
 * supervised loop and joins it. A loop the Bounded policy disabled ends the command with {@link
 * DashboardDisabledException} — exit status 1, with nothing printed beyond the loop's own ERROR —
 * while a join that ends without a give-up returns normally, as before. FR9 of add-dashboard-page:
 * the watch page carries the meta-refresh a one-shot page never does.
 */
class DashboardCommandWatchSpec extends Specification implements ApplicationArgumentsFixture {

    @TempDir
    Path tempDir

    Path projectDir
    Path factoryHome

    def setup() {
        projectDir = GnomishProjectFixture.writeGnomishProject(tempDir.resolve('project'))
        factoryHome = tempDir.resolve('gnomish-home')
    }

    private DashboardCommand newCommand(Sleeper sleeper) {
        DashboardCommandFixture.command(factoryHome, projectDir, new RecordingReadOnlyTracker([], []), sleeper, 'watch-instance')
    }

    private void runWatch(DashboardCommand command, Path out) {
        command.run(args('dashboard', "--dir=${projectDir}".toString(), "--out=${out}".toString(), '--watch'))
    }

    @Timeout(5)
    def "--watch enters the watch loop instead of rendering once"() {
        given: 'a sleeper whose render-cadence waits kill the loop thread, so the Bounded loop gives up and the command returns'
        def sleptDurations = Collections.synchronizedList([])
        def command = newCommand(DashboardCommandFixture.givingUpSleeper { Duration d ->
            sleptDurations << d
        })
        def out = tempDir.resolve('watch.html')

        when:
        runWatch(command, out)

        then: 'the loop gave up, so the command ends disabled'
        thrown(DashboardDisabledException)

        and: 'every loop thread rendered, then waited the render cadence'
        Files.exists(out)
        !sleptDurations.isEmpty()
        sleptDurations.every { it == DashboardWatch.RENDER_CADENCE }

        and: 'a watch-mode page carries the meta-refresh and the watch mode a one-shot page never does'
        Files.readString(out).contains('data-mode="watch"')
        Files.readString(out).contains('<meta http-equiv="refresh" content="10">')
    }

    @Timeout(5)
    def "FR9: a --watch whose loop gives up ends the command with exit status 1"() {
        given: 'every render-cadence wait kills the loop thread and every backoff returns, so the Bounded policy gives up'
        def command = newCommand(DashboardCommandFixture.givingUpSleeper())

        when:
        runWatch(command, tempDir.resolve('dead.html'))

        then: 'the command ends with the disabled carrier, which the exit-code mapper turns into 1'
        def disabled = thrown(DashboardDisabledException)
        new RunExitCodeMapper().getExitCode(disabled) == 1

        and: 'the runner prints nothing more: the loop already logged its give-up ERROR'
        RunExceptionReporting.calmLine(disabled) == ''
    }

    @Timeout(5)
    def "FR9: a --watch that ends without a give-up keeps its normal exit"() {
        given: 'a loop that renders, then waits until released'
        def waiting = new CountDownLatch(1)
        def released = new CountDownLatch(1)
        def sleeper = { Duration d ->
            waiting.countDown()
            released.await(5, TimeUnit.SECONDS)
            throw new SupervisedLoopHarness.Unrenderable()
        } as Sleeper
        def command = newCommand(sleeper)
        def out = tempDir.resolve('watch-ended.html')
        def failure = new AtomicReference<Throwable>()
        def runner = Thread.ofPlatform().start {
            try {
                runWatch(command, out)
            } catch (Throwable t) {
                failure.set(t)
            }
        }

        when: 'the command is joining its rendered, waiting loop and that join is interrupted -- the loop never gave up'
        while (runner.isAlive() && !waiting.await(10, TimeUnit.MILLISECONDS)) {
            // a command that never started its loop returns on its own; stop polling then
        }
        runner.interrupt()
        runner.join()

        then: 'the loop really ran: it rendered the page and entered its render-cadence wait'
        waiting.count == 0
        Files.exists(out)

        and: 'the command returns normally: no disabled carrier, so the exit stays 0'
        failure.get() == null

        cleanup: 'let the background loop die out'
        released.countDown()
    }
}
