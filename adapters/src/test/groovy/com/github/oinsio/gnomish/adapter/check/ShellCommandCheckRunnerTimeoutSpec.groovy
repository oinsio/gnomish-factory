package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR12, UX4, NFR-O1 of bound-subprocess-commands: a {@code command} check is bounded by the
 * installation's check timeout. A command that never exits is killed tree-wide and resolves as an
 * ordinary quality failure carrying the tail captured so far, so the stage's retry loop proceeds
 * instead of the run hanging; a command that finishes in time is classified exactly as before.
 */
class ShellCommandCheckRunnerTimeoutSpec extends Specification implements ShellCommandCheckRunnerTestSupport {

    @TempDir
    Path tempDir

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300)

    def runner = new ShellCommandCheckRunner(new VirtualClock()).withCheckTimeout(SHORT_TIMEOUT)

    // Scenario: verification/check-provider-model / A hung check fails instead of hanging the run
    @Timeout(30)
    def "FR12, UX4: a check that never exits is a quality failure carrying the tail captured so far"() {
        given: 'a check that has printed a distinctive line and never exits on its own'
        // FR18 of fix-terminal-receipt-convergence: the line is in the pipe before the wait starts,
        // and the latch shows the drain read it — the line is delivered by the spec, not by the
        // shell's start time racing the 300 ms deadline.
        def process = FakeProcess.running()
        boolean drainReadTheLine = false
        process.onWaitReturn {
            drainReadTheLine = process.awaitStdoutRead(Duration.ofSeconds(10))
        }
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            process.writeStdout('hung-output\n')
            process
        } as ProcessLaunch
        def fakeRunner = runner.withEnvironments(hostEnvironments(launch))

        when:
        def verdict = fakeRunner.run(command('the-hung-check'), workspace())

        then: 'a quality failure, not a hang and not an infrastructure verdict'
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].message().toLowerCase().contains('timed out')
        fail.findings()[0].details().contains('hung-output')

        and: 'the drain read the line through the pipe'
        process.awaitStdoutRead(Duration.ZERO)
    }

    /** The host environment of a check, its process started by {@code launch} instead of the OS. */
    private CheckEnvironmentSource hostEnvironments(ProcessLaunch launch) {
        def host = new HostTaskExecutionEnvironment(tempDir, new VirtualClock(), ChildEnvAllowlist.none(), launch)
        host.materialize('workspace:fake', null)
        Stub(CheckEnvironmentSource) {
            acquire(_, _) >> Stub(CheckEnvironmentSource.Acquired) {
                environment() >> host
                close() >> { host.dispose() }
            }
        }
    }

    @Timeout(30)
    def "FR12: the hung check's own process tree does not outlive the check"() {
        given: 'a command whose shell spawns a child that would outlive a parent-only kill'
        def pidFile = tempDir.resolve('child.pid')
        def check = command("sh -c 'echo \$\$ > ${pidFile}; sleep 30' & wait")

        and: 'a roomier deadline than the shared 300 ms one, so the child records its pid before the kill'
        // The first exec of a freshly written script can take hundreds of milliseconds (macOS
        // scans new executables); this feature tests the tree kill, not the bound's tightness.
        def treeKillRunner = new ShellCommandCheckRunner(new VirtualClock()).withCheckTimeout(Duration.ofSeconds(2))

        when:
        def verdict = treeKillRunner.run(check, workspace())

        then: 'the descendant recorded its pid and is no longer alive once the check resolves'
        verdict instanceof Verdict.Fail
        def childPid = recordedPid(pidFile)
        !ProcessHandle.of(childPid).map { it.isAlive() }.orElse(false)
    }

    /**
     * Bounded poll for the pid the descendant records: the write races only the check's own
     * deadline, so by the time {@code run} has returned the file is normally already there — the
     * poll covers the last sliver where the shell was killed between creating and filling it.
     */
    private static long recordedPid(Path pidFile) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() <deadline) {
            if (Files.exists(pidFile)) {
                String text = Files.readString(pidFile).trim()
                if (!text.isEmpty()) {
                    return text as long
                }
            }
            Thread.sleep(25)
        }
        throw new AssertionError("the check's child never recorded its pid in ${pidFile}" as Object)
    }

    // Scenario: verification/check-provider-model / A timed-out check is logged with its bound
    @Timeout(30)
    def "NFR-O1: the expiry logs one WARN naming the check, the elapsed time and the configured deadline"() {
        given:
        def check = command('while true; do sleep 1; done')

        when:
        def events = capture(CommandProcessRunner) {
            runner.run(check, workspace())
        }

        then: 'one WARN names the check id and the deadline an operator would raise'
        def warnings = events.findAll { it.level.toString() == 'WARN' }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.COMMAND_CHECK_TIMED_OUT.head())
        warnings[0].formattedMessage.contains('command check timed out')
        warnings[0].formattedMessage.contains('while true')
        warnings[0].formattedMessage.contains("deadline=${SHORT_TIMEOUT}")

        and: 'the elapsed it reports is the check\'s own, measured from its start'
        def reported = Duration.parse(
                (warnings[0].formattedMessage =~ /elapsed=(PT[^,\s]+)/)[0][1] as String)
        reported >= SHORT_TIMEOUT
        reported <Duration.ofMinutes(1)
    }

    def "NFR-O1: a check that answers in time writes no WARN at all"() {
        expect: 'an operator\'s WARN log means a bound actually fired'
        capture(CommandProcessRunner) {
            runner.run(command('echo done'), workspace())
        }.findAll { it.level.toString() == 'WARN' }.isEmpty()
    }

    @Timeout(30)
    def "FR6: an interrupted check is CannotVerify - infrastructure, never a quality failure"() {
        given: 'the calling thread is already interrupted when the wait starts'
        def check = command('while true; do sleep 1; done')

        when:
        def verdict = null
        def events = capture(CommandProcessRunner) {
            def worker = Thread.start {
                Thread.currentThread().interrupt()
                verdict = runner.run(check, workspace())
            }
            worker.join()
        }

        then: 'the shutdown never burns a stage attempt on a verdict nobody established'
        verdict instanceof Verdict.CannotVerify
        (verdict as Verdict.CannotVerify).reason().forLog().toLowerCase().contains('interrupted')

        and: 'NFR-O2: the WARN names interruption, not a deadline the check never reached'
        def warnings = events.findAll { it.level.toString() == 'WARN' }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.COMMAND_CHECK_INTERRUPTED.head())
        warnings[0].formattedMessage.contains('command check interrupted')
        !warnings[0].formattedMessage.contains('deadline=')
    }

    // Scenario: verification/check-provider-model / A check that finishes in time is untouched
    def "NFR-R3: a check that finishes in time keeps its exit code, tail and classification"() {
        given:
        def check = command('echo fine-output; exit 3')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings()[0].message().contains('3')
        fail.findings()[0].details().contains('fine-output')
    }

    def "NFR-R3: a passing check under the bound is still a Pass"() {
        when:
        def verdict = runner.run(command('exit 0'), workspace())

        then:
        verdict instanceof Verdict.Pass
    }
}
