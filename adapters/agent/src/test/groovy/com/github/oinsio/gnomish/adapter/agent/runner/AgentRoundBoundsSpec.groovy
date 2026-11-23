package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The three bounds of an agent round on virtual time (FR1, FR8, NFR-R1 of extract-agent-round-runner):
 * the startup-read bound until the CLI's first bytes, the silence bound from its last ones, the overall
 * bound from the start. Each CLI is a live {@link FakeProcess} that never exits on its own, so each
 * outcome is the bound's whatever order the reader thread runs in: the bound under test is the short
 * one, the others an hour or more, and a line the CLI wrote is read long before an hour of virtual
 * time passes in 100 ms slices. The fake dialect declares {@code streams_progress} unless a feature
 * says otherwise; one that declares none is held to the overall bound alone (FR16).
 */
@Timeout(60)
class AgentRoundBoundsSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    def cleanup() {
        Thread.interrupted()
    }

    // Scenario: executor/agent-round-runner / Silence bound on virtual time
    def "NFR-R1: a CLI that wrote #wrote and then went quiet is killed by the #bound bound, the result naming it"() {
        given:
        def process = FakeProcess.running()
        process.writeStdout(stdout)
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent, bounds: bounds)

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), process)

        then: 'the tree is killed and the result names the bound'
        !process.alive
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': the ${bound} bound of PT0.35S expired and its process tree was killed"

        where:
        wrote | stdout | bound | bounds
        'one event' | 'hello m s\n' | 'silence' | new ProgramBounds(Duration.ofHours(1), Duration.ofMillis(350), Duration.ofHours(2))
        'nothing' | '' | 'startup-read' | new ProgramBounds(Duration.ofMillis(350), Duration.ofHours(1), Duration.ofHours(2))
        'nothing' | '' | 'overall' | new ProgramBounds(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(350))
    }

    // Scenario: executor/agent-round-runner / A dialect that declares no liveness is not killed on silence
    def "FR16: a dialect that declares no streams_progress writes nothing past the startup-read and silence bounds, then its result, and completes"() {
        given: 'quiet bounds of 350 ms, the overall bound out of reach, a CLI quiet for 2 s and then done'
        def clock = new VirtualClock()
        def process = FakeProcess.running()
        Instant started = clock.instant()
        def sleeper = { Duration slept ->
            clock.advance(slept)
            if (process.alive && Duration.between(started, clock.instant()) >= Duration.ofSeconds(2)) {
                process.writeStdout('done ok\n')
                process.exit(0)
            }
        } as Sleeper
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent,
        bounds: new ProgramBounds(Duration.ofMillis(350), Duration.ofMillis(350), Duration.ofHours(1)),
        dialect: new FakeDialect(capabilities: [Capability.CANCEL]), time: VirtualTimeEquipment.on(clock, sleeper))

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), process)

        then: 'the round outlived both quiet bounds and completed with the result the CLI wrote'
        Duration.between(started, clock.instant()) >= Duration.ofSeconds(2)
        result.status() == Status.COMPLETED

        and: 'the request it rendered carries no startup-read and no silence hint, only the overall one'
        fixture.dialect.rendered*.bounds()*.startupReadMillis() == [null]
        fixture.dialect.rendered*.bounds()*.silenceMillis() == [null]
        fixture.dialect.rendered*.bounds()*.overallMillis() == [
            Duration.ofHours(1).toMillis()
        ]
    }

    // Scenario: executor/agent-round-runner / A dialect that declares no liveness is still held to the overall bound
    def "FR16: a dialect that declares no streams_progress and writes nothing past its overall bound is killed, failed/infrastructure naming the overall bound"() {
        given: 'quiet bounds shorter than the overall bound, which no quiet bound may pre-empt'
        def process = FakeProcess.running()
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent,
        bounds: new ProgramBounds(Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(350)),
        dialect: new FakeDialect(capabilities: []))

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), process)

        then:
        !process.alive
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': the overall bound of PT0.35S expired and its process tree was killed"
    }

    // NFR-R1: an interrupt during the wait kills the tree, says so, and is left set
    def "NFR-R1: an interrupted round is killed, failed/infrastructure saying so, the flag left set"() {
        given:
        def clock = new VirtualClock()
        def process = FakeProcess.running()
        def sleeper = { Duration d ->
            clock.advance(d)
            Thread.currentThread().interrupt()
        } as Sleeper
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent,
        time: VirtualTimeEquipment.on(clock, sleeper))

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), process)

        then:
        result.reason().raw().startsWith("agent executor 'fake': the round was interrupted and its process tree killed")
        Thread.interrupted()
        !process.alive
    }
}
