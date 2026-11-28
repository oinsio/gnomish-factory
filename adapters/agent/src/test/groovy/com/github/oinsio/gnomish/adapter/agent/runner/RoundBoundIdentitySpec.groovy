package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
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
 * The identity of the {@code RoundBounds} row (FR7, NFR-R1 of extract-agent-round-runner; design D6 as
 * amended 2026-11-21): the overall bound a round is killed at and the overall hint of the request the
 * dialect renders are one value — the {@code roundTimeout} the request's settings declare, or the
 * factory's overall bound when they declare none — asserted end to end through the real runner, the
 * real wait and reader, on virtual time.
 *
 * <p>Each CLI is a live {@link FakeProcess} that writes a line on every tick of the wait, from the
 * wait's own sleeper, so it is "still writing events" whatever the reader's thread does, and exits only
 * when a spec says so. The bounds are scaled to ticks (100 ms each, every one also a real bounded join
 * on the reader): the factory's overall bound 400 ms, a declared {@code roundTimeout} 1.5 s; the wait
 * reaches each deadline exactly, so the virtual time the round took is the bound it was killed at.
 */
@Timeout(60)
class RoundBoundIdentitySpec extends Specification {

    /** The factory's bounds: nothing quiet is killed, the overall bound is the short one. */
    static final ProgramBounds FACTORY =
    new ProgramBounds(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMillis(400))

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    VirtualClock clock = new VirtualClock()

    FakeProcess process = FakeProcess.running()

    def cleanup() {
        Thread.interrupted()
    }

    // Scenario: executor/agent-round-runner / A declared roundTimeout is the round's overall bound
    // Scenario: executor/agent-round-runner / A judge vote is bounded by its check's roundTimeout
    def "FR7, NFR-R1: a #role round whose settings declare roundTimeout runs past the factory's overall bound and is killed at exactly its own value, the hint equal to it"() {
        given: 'a CLI that writes an event on every tick'
        def fixture = fixture({ process.writeStdout('use Bash\n') })
        def started = clock.instant()

        when:
        def result = fixture.start(request(role, [roundTimeout: 'PT1.5S']), process)

        then: 'not killed at the factory bound, but at the declared one'
        Duration.between(started, clock.instant()) == Duration.ofMillis(1500)
        !process.alive
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': the overall bound of PT1.5S expired and its process tree was killed"

        and: 'the overall hint the dialect rendered is the value the round was killed at'
        fixture.dialect.rendered*.bounds()*.overallMillis() == [1500L]
        fixture.dialect.rendered*.bounds()*.silenceMillis() == [FACTORY.silence().toMillis()]
        fixture.dialect.rendered*.bounds()*.startupReadMillis() == [FACTORY.startup().toMillis()]

        where:
        role << [Role.TRANSFORM, Role.VERDICT]
    }

    // Scenario: executor/agent-round-runner / Without a roundTimeout the factory's overall bound applies
    def "FR7, NFR-R1: a round whose settings declare no roundTimeout is killed at the factory's overall bound, the hint equal to it"() {
        given:
        def fixture = fixture({ process.writeStdout('use Bash\n') })
        def started = clock.instant()

        when:
        def result = fixture.start(request(Role.TRANSFORM, [maxTurns: 3]), process)

        then:
        Duration.between(started, clock.instant()) == FACTORY.overall()
        !process.alive
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'fake': the overall bound of PT0.4S expired and its process tree was killed"
        fixture.dialect.rendered*.bounds()*.overallMillis() == [FACTORY.overall().toMillis()]
    }

    // Scenario: executor/agent-round-runner / A liveness line during a long tool call keeps the round alive
    def "NFR-R1: a CLI that writes nothing but a liveness line every 3 ticks for three silence bounds is not killed on silence"() {
        given: 'a silence bound of 1 s, the overall bound out of reach, and a CLI quiet but for its liveness lines'
        def silence = new ProgramBounds(Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofHours(1))
        Instant started = clock.instant()
        def ticks = 0
        def fixture = fixture({
            ticks++
            if (Duration.between(started, clock.instant()) >= Duration.ofSeconds(3)) {
                process.writeStdout('done ok\n')
                process.exit(0)
            } else if (ticks % 3 == 0) {
                process.writeStdout('{"type":"system","subtype":"tool_progress","heartbeat":true}\n')
            }
        }, silence)

        when:
        def result = fixture.start(request(Role.TRANSFORM, [:]), process)

        then: 'the round outlived three silence bounds and ended as the CLI ended it'
        Duration.between(started, clock.instant()) >= Duration.ofSeconds(3)
        result.status() == Status.COMPLETED
    }

    /** The runner over a sleeper that advances the clock and then runs {@code onTick}. */
    private RunnerFixture fixture(Closure onTick, ProgramBounds bounds = FACTORY) {
        def sleeper = { Duration slept ->
            clock.advance(slept)
            onTick()
        } as Sleeper
        new RunnerFixture(workspace: workspace, stderrParent: stderrParent, bounds: bounds,
        time: VirtualTimeEquipment.on(clock, sleeper))
    }

    private static ExecutorRequest request(Role role, Map settings) {
        def base = RunnerFixture.request(role)
        new ExecutorRequest(base.role(), base.workspace(), base.policy(), base.roleInputs(),
                ExecutorContractJson.settingsTree(settings), base.bounds(), [], null)
    }
}
