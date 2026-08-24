package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.board.BoardModel
import com.github.oinsio.gnomish.board.ReadySummary
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.testsupport.StepClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * FR7, FR8, FR9, NFR-P1, NFR-R1, NFR-R2, M2 of add-dashboard-page (task 4.4, design D4, D9):
 * {@link DashboardWatchLoop} re-renders on the render cadence, refreshes the board only on its own
 * slower cadence (a {@link DashboardBoardCache} carries the last model between refreshes), keeps
 * writing through a board-fetch failure, and swallows an output-write failure rather than exiting
 * (NFR-R1). The cadence budget over an hour (one board fetch per board interval, never per render)
 * is the M2 success-metric assertion. Drives {@link
 * DashboardWatchLoop#renderOnce} directly — one cycle at a time, mirroring how {@code
 * FeedAutomatonSpec} drives {@code step()} — over a {@link StepClock} so cycle timing is
 * deterministic without a real sleep.
 *
 * <p>NFR-P1, M1 of redesign-dashboard: the redesign is presentation-only, so these cadence and
 * board-fetch-budget assertions must hold unchanged across it — the same render cadence, the same
 * board interval, no new data source — and the {@code #staleness-banner} element is gone from the
 * page the loop writes.
 */
class DashboardWatchLoopSpec extends Specification {

    private static final String INSTANCE_NAME = 'watch-instance'
    private static final Instant T0 = Instant.parse('2026-08-06T00:00:00Z')

    @TempDir
    Path homeDir

    Path outputFile

    def model = new BoardModel([], [], [], new ReadySummary(0, 0, 0, 0, 0), false, T0)

    def setup() {
        outputFile = homeDir.resolve('dashboard.html')
    }

    private DashboardWatchLoop newLoop(List<Instant> instants) {
        new DashboardWatchLoop(new DashboardRenderCycle(), Stub(Sleeper), new StepClock(instants))
    }

    def "a render cycle between board refreshes reuses the cached model without refetching"() {
        given: 'two cycles inside the 60s board cadence'
        def loop = newLoop([T0, T0.plusSeconds(10)])
        def fetchCount = 0
        def fetch = { -> fetchCount++; model }

        when:
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)

        then:
        fetchCount == 1
    }

    def "a render cycle at or past the board cadence refetches"() {
        given: 'the second cycle lands exactly at the 60s board cadence'
        def loop = newLoop([T0, T0.plusSeconds(60)])
        def fetchCount = 0
        def fetch = { -> fetchCount++; model }

        when:
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)

        then:
        fetchCount == 2
    }

    def "M2: over one hour of render cycles the board is fetched once per board cadence, never per render"() {
        given: 'one full hour of render cycles at the 10s render cadence'
        long renderStep = DashboardWatchLoop.RENDER_CADENCE.seconds
        int cycles = (int) (Duration.ofHours(1).seconds / renderStep)
        def instants = (0..<cycles).collect { T0.plusSeconds(it * renderStep) }
        def loop = newLoop(instants)
        def fetchCount = 0
        def fetch = { -> fetchCount++; model }

        when: 'every render cycle in the hour runs'
        cycles.times {
            loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)
        }

        then: 'tracker reads stay within the board-cadence budget: one fetch per board interval, not per render'
        fetchCount == (int) (Duration.ofHours(1).seconds / DashboardWatchLoop.BOARD_CADENCE.seconds)
    }

    def "NFR-R1: an output-write failure is logged and swallowed so the watch loop keeps running"() {
        given: 'an output path whose parent cannot be created — a regular file sits where the directory must be'
        def blocker = Files.createFile(homeDir.resolve('blocker'))
        def unwritable = blocker.resolve('dashboard.html')
        def loop = newLoop([T0])

        when: 'a cycle renders but the atomic write cannot place its file'
        loop.renderOnce(homeDir, INSTANCE_NAME, unwritable, { -> model })

        then: 'the write failure never propagates — the loop survives to render the next cycle'
        noExceptionThrown()
        !Files.exists(unwritable)
    }

    @Timeout(5)
    def "run() renders a cycle then sleeps for exactly the render cadence before the next one"() {
        given: 'a sleeper that records the requested duration and stops the otherwise-infinite loop'
        def sleptDurations = []
        def sleeper = { Duration d ->
            sleptDurations << d; throw new RuntimeException('stop after one cycle')
        } as Sleeper
        def loop = new DashboardWatchLoop(new DashboardRenderCycle(), sleeper, new StepClock([T0]))

        when:
        loop.run(homeDir, INSTANCE_NAME, outputFile, { -> model })

        then:
        thrown(RuntimeException)
        Files.exists(outputFile)
        sleptDurations == [
            DashboardWatchLoop.RENDER_CADENCE
        ]
    }

    @Timeout(10)
    def "run() exits when the calling thread is interrupted"() {
        given: 'a loop on the production sleeper, whose real 10s sleep only an interrupt can cut short'
        def loop = new DashboardWatchLoop(
                new DashboardRenderCycle(), new ThreadSleeper(), new StepClock([T0, T0.plusSeconds(10)]))

        and: 'the loop runs on its own thread, exactly like gnomish dashboard --watch'
        def thread = new Thread({
            loop.run(homeDir, INSTANCE_NAME, outputFile, {
                -> model
            })
        })

        when: 'the first cycle lands and the calling thread is then interrupted mid-sleep'
        thread.start()
        new PollingConditions(timeout: 5).eventually {
            assert Files.exists(outputFile)
        }
        thread.interrupt()
        thread.join(3000)

        then: 'the loop observed the interrupt and returned instead of sleeping on forever'
        !thread.alive
    }

    def "each cycle atomically replaces the output file with a complete, self-contained page"() {
        given:
        def loop = newLoop([T0])

        when:
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, { -> model })

        then:
        Files.exists(outputFile)
        def html = Files.readString(outputFile)
        html.startsWith('<!doctype html>')
        html.contains('</html>')
    }

    def "a watch-mode cycle marks the page as watch mode and bakes its meta-refresh"() {
        given:
        def loop = newLoop([T0])

        when:
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, { -> model })

        then: 'the mode the static script reads to arm its stale degradation (FR3, FR10 of redesign-dashboard)'
        def html = Files.readString(outputFile)
        html.contains('data-mode="watch"')
        html.contains('<meta http-equiv="refresh" content="10">')

        and: 'the freshness strip replaced the full-viewport banner entirely'
        !html.contains('staleness-banner')
        html.contains('id="freshness"')
    }

    def "a board fetch failure degrades the board section but the cycle still writes a complete page"() {
        given:
        def loop = newLoop([T0])

        when:
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, {
            -> throw new RuntimeException('tracker down')
        })

        then:
        def html = Files.readString(outputFile)
        html.contains('unavailable')
        html.contains('tracker down')
    }

    def "the loop keeps running across a board outage: the next cycle after recovery refreshes normally"() {
        given: 'the fetch fails on the first (due) cycle and succeeds on the second, past the board cadence'
        def loop = newLoop([T0, T0.plusSeconds(60)])
        def attempt = 0
        def fetch = {
            ->
            attempt++
            if (attempt == 1) {
                throw new RuntimeException('tracker down')
            }
            model
        }

        when:
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)
        loop.renderOnce(homeDir, INSTANCE_NAME, outputFile, fetch)

        then:
        attempt == 2
        !Files.readString(outputFile).contains('unavailable')
    }
}
