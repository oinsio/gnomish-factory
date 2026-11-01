package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link ProgressWait}, the wait of a {@code streams_progress} run (NFR-R2 of
 * define-executor-contract), on virtual time: the overall bound ends a run that keeps talking, no
 * slice of the wait is longer than {@link ProgressWait#TICK}, and an interrupt kills the tree and is
 * left set. The fake never exits on its own, so each outcome is the bound's or the interrupt's
 * whatever order the reader thread runs in.
 */
@Timeout(60)
class ProgressWaitSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    def cleanup() {
        Thread.interrupted()
    }

    // NFR-R2: the overall bound ends a streaming run before its silence bound does, and no wait slice exceeds the tick
    def "NFR-R2: a streaming run past its overall bound is killed naming it, waiting in slices of at most a tick"() {
        given:
        def clock = new VirtualClock()
        List<Duration> slept = []
        def sleeper = { Duration d ->
            slept << d
            clock.advance(d)
        } as Sleeper
        def process = FakeProcess.running()
        def bounds = new ProgramBounds(Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMillis(350))
        def fixture = new FakeProcessRun(workspace: workspace, stderrParent: stderrParent,
        capabilities: [Capability.STREAMS_PROGRESS], bounds: bounds, time: VirtualTimeEquipment.on(clock, sleeper))

        when:
        def result = fixture.start(process, { Path out, Path answer, Path err -> })

        then:
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('the overall bound of PT0.35S expired')
        !process.alive
        slept.every { it <= ProgressWait.TICK }
        slept.last() == Duration.ofMillis(50)
    }

    // NFR-R2: an interrupt during the wait kills the tree and leaves the flag set
    def "NFR-R2: an interrupted streaming run is killed, failed/infrastructure saying so, the flag left set"() {
        given:
        def clock = new VirtualClock()
        def process = FakeProcess.running()
        def sleeper = { Duration d ->
            clock.advance(d)
            Thread.currentThread().interrupt()
        } as Sleeper
        def fixture = new FakeProcessRun(workspace: workspace, stderrParent: stderrParent,
        capabilities: [Capability.STREAMS_PROGRESS], time: VirtualTimeEquipment.on(clock, sleeper))

        when:
        def result = fixture.start(process, { Path out, Path answer, Path err -> })

        then:
        result.reason().contains('the run was interrupted and its process tree killed')
        Thread.interrupted()
        !process.alive
    }
}
