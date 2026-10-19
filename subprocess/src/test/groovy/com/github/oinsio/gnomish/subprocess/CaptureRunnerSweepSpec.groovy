package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.WritingHolder
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep.Answer
import java.time.Duration
import spock.lang.Specification

/**
 * FR21, design D19 of fix-terminal-receipt-convergence: the capture runner sweeps the command's
 * process group once the drains are joined, on every termination — the cut clean exit it refuses
 * included, cut by a {@link WritingHolder} (D4 of make-pipe-reads-non-parking) — and its result is
 * otherwise the one it had without the sweep. The process is a {@link
 * FakeProcess} naming a group the scripted signal answers for, so no real group is signalled; the
 * real {@code kill} is {@code ProcessGroupSweepSpec}'s.
 */
class CaptureRunnerSweepSpec extends Specification {

    private static final ProcessBuilder UNLAUNCHABLE = new ProcessBuilder('/nonexistent/gnomish-no-such-binary')

    /** A group that answers the first probe, so one round runs, and refuses the second. */
    private static final List<Answer> ONE_SURVIVOR = [
        Answer.ANSWERED,
        Answer.REFUSED
    ]

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21: a clean exit is swept after the drains, and its capture is the one it had"() {
        given:
        List<String> sent = []
        FakeProcess process = FakeProcess.running().leading(4242)
        process.writeStdout('spoken')
        process.exit(3)

        when:
        Captured captured = runner(process, sent).run(UNLAUNCHABLE, Duration.ofSeconds(30))

        then: 'the group was probed, killed once, and found empty'
        sent == [
            '0 4242',
            'KILL 4242',
            '0 4242'
        ]
        captured.sweep() == new GroupSweep(1, true)

        and: 'the result is the command\'s own'
        captured.termination() == Termination.EXITED
        captured.exitCode() == 3
        captured.stdout() == 'spoken'
        !captured.truncated()
    }

    def "FR21, FR17: a cut clean exit is swept before it is refused, the sweep inside the refusal"() {
        given: 'a clean exit whose stdout a descendant holds and keeps writing to, so the bound cuts it'
        List<String> sent = []
        FakeProcess process = FakeProcess.running().leading(4242)
        process.holdOpen()
        WritingHolder.on(process.stdoutPipe())
        process.exit(0)

        when:
        runner(process, sent, new ProcessSupervisor(Duration.ofMillis(50))).run(UNLAUNCHABLE, null)

        then:
        CutCaptureException refusal = thrown()
        sent.first() == '0 4242'
        refusal.captured().sweep() == new GroupSweep(1, true)

        cleanup:
        process.release()
    }

    def "FR21: a timed-out command is swept after its tree kill"() {
        given: 'a fake that never exits on its own'
        List<String> sent = []
        FakeProcess process = FakeProcess.running().leading(4242)

        when:
        Captured captured = runner(process, sent, new ProcessSupervisor(Duration.ofMillis(50)))
                .run(UNLAUNCHABLE, Duration.ofMillis(50))

        then:
        captured.termination() == Termination.TIMED_OUT
        sent.first() == '0 4242'
        captured.sweep() == new GroupSweep(1, true)
    }

    def "FR21: an interrupted wait is swept too, and the interrupt is still the caller's"() {
        given:
        List<String> sent = []
        FakeProcess process = FakeProcess.running().leading(4242)
        Thread.currentThread().interrupt()

        when:
        Captured captured = runner(process, sent, new ProcessSupervisor(Duration.ofMillis(50)))
                .run(UNLAUNCHABLE, Duration.ofSeconds(30))

        then:
        captured.termination() == Termination.INTERRUPTED
        sent.first() == '0 4242'
        Thread.interrupted()
    }

    // Scenario: lifecycle/subprocess-supervision / A failed launch is never swept
    def "FR21: a launch that threw is never swept"() {
        given: 'a launch that fails before any process exists'
        List<String> sent = []
        ProcessLaunch refusing = { ProcessBuilder builder, StdinSource stdin ->
            throw new IOException('no such binary')
        } as ProcessLaunch

        when:
        new CaptureRunner(new ProcessSupervisor(), refusing, new ProcessGroupSweep(new ProcessProbes(ONE_SURVIVOR, sent)))
                .run(UNLAUNCHABLE, Duration.ofSeconds(30))

        then: 'the launch failure stands, and no group was signalled'
        thrown(IOException)
        sent == []
    }

    private static CaptureRunner runner(
            FakeProcess process, List<String> sent, ProcessSupervisor supervisor = new ProcessSupervisor()) {
        new CaptureRunner(supervisor, { ProcessBuilder builder, StdinSource stdin ->
            process
        } as ProcessLaunch, new ProcessGroupSweep(new ProcessProbes(ONE_SURVIVOR, sent)))
    }
}
