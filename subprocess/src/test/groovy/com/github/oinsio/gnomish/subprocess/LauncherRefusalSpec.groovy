package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.RefusingLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 as amended 2026-10-17, second, of fix-terminal-receipt-convergence: when the
 * process-group launcher started and could not exec the program — the residue the owner's lookup
 * cannot see — it writes one line of its own and exits by errno, 127 for {@code ENOENT} and 126
 * otherwise; a waiter that captures stderr whole names that a launch failure only by the code and
 * the line together. On macOS the line is the project's sentinel, written by the perl one-liner
 * under {@code no warnings}; on Linux it is util-linux's {@code failed to execute}.
 */
class LauncherRefusalSpec extends Specification {

    @TempDir
    Path inputs

    @TempDir
    Path work

    // Scenario: lifecycle/subprocess-supervision / A launcher that could not exec is a launch failure where stderr is captured whole
    def "FR21: the platform launcher's refusal of #shape is its one line, exit #code, and a launch failure"() {
        given: 'the platform\'s own launcher, run past the lookup, with perl\'s warnings forced on'
        Files.writeString(work.resolve('not-executable'), 'data')
        String target = path.replace('@work', work.toString())
        ProcessBuilder builder = new ProcessBuilder(new HostLauncher(System.getProperty('os.name'), System.getenv('PATH'), HostLauncher.KILL).prefix() + [target])
        TestChildEnvironment.cleared(builder)
        builder.environment().put('PERL5OPT', '-w')

        when:
        Process process = builder.redirectInput(new File('/dev/null')).start()
        String stderr = process.errorStream.text
        process.waitFor(10, TimeUnit.SECONDS)

        then: 'one line, the launcher\'s, and the errno\'s code'
        process.exitValue() == code
        stderr.readLines().size() == 1
        ProcessLaunch.launcherCouldNotExec(Termination.EXITED, code, stderr)

        and: 'on macOS, the line the streaming waiters\' fixture writes'
        !System.getProperty('os.name').startsWith('Mac') || code != 127 || stderr == RefusingLaunch.line(target)

        where:
        shape | path || code
        'an absent path' | '/nonexistent/gnomish-no-such-binary' || 127
        'a file without the x bit' | '@work/not-executable' || 126
    }

    // Scenario: lifecycle/subprocess-supervision / A launcher that could not exec is a launch failure where stderr is captured whole
    def "FR21: a refusal the whole-capture waiter observes is the launch exception, naming the configured binary"() {
        when:
        new CaptureRunner(new ProcessSupervisor(), new RefusingLaunch())
                .run(new ProcessBuilder('gnomish-refused', 'arg'), Duration.ofSeconds(30))

        then:
        IOException refused = thrown()
        refused.message.startsWith('Cannot run program "gnomish-refused"')
        refused.message.contains('exit 127')
    }

    // Scenario: lifecycle/subprocess-supervision / A launcher that could not exec is a launch failure where stderr is captured whole
    def "FR21: a program that itself writes #shape is a result"() {
        given:
        ProcessBuilder builder = new ProcessBuilder('sh', '-c', script)
        TestChildEnvironment.cleared(builder)

        when:
        Captured captured = new CaptureRunner(new ProcessSupervisor(), ProcessLaunch.grouped(inputs))
                .run(builder, Duration.ofSeconds(30))

        then:
        captured.termination() == Termination.EXITED
        captured.exitCode() == code

        where:
        shape | script || code
        'the code without the sentinel' | 'echo "Cannot exec what you asked" >&2; exit 127' || 127
        'the sentinel without the code' | 'echo "gnomish-launch: cannot exec /x: No such file or directory" >&2; exit 1' || 1
    }

    // Scenario: lifecycle/subprocess-supervision / A launcher that could not exec is a launch failure where stderr is captured whole
    def "FR21: the launcher's refusal is judged by its code and its own line together: #shape"() {
        expect:
        ProcessLaunch.launcherCouldNotExec(termination, code, stderr) == refused

        where:
        shape | termination | code | stderr || refused
        'util-linux, not found' | Termination.EXITED | 127 | 'setsid: failed to execute /x/git: No such file or directory\n' || true
        'util-linux, not executable' | Termination.EXITED | 126 | 'setsid: failed to execute /x/git: Permission denied\n' || true
        'the sentinel, not found' | Termination.EXITED | 127 | 'gnomish-launch: cannot exec /x/git: No such file or directory\n' || true
        'the sentinel, not executable' | Termination.EXITED | 126 | 'gnomish-launch: cannot exec /x/git: Permission denied\n' || true
        'the sentinel after other output' | Termination.EXITED | 127 | 'warming up\ngnomish-launch: cannot exec /x/git: No such file or directory\n' || true
        'the code alone' | Termination.EXITED | 127 | 'sh: git: command not found\n' || false
        'the sentinel alone' | Termination.EXITED | 1 | 'gnomish-launch: cannot exec /x/git: No such file or directory\n' || false
        'the util-linux text alone' | Termination.EXITED | 1 | 'setsid: failed to execute /x/git: No such file or directory\n' || false
        'perl\'s own words, no longer the launcher\'s' | Termination.EXITED | 127 | 'Can\'t exec "/x/git": No such file or directory at -e line 1.\n' || false
        'a killed invocation' | Termination.TIMED_OUT | 127 | 'gnomish-launch: cannot exec /x/git: No such file or directory\n' || false
        'an exit of 0' | Termination.EXITED | 0 | 'gnomish-launch: cannot exec /x/git: No such file or directory\n' || false
        'an exit of 125' | Termination.EXITED | 125 | 'gnomish-launch: cannot exec /x/git: No such file or directory\n' || false
        'an exit of 128' | Termination.EXITED | 128 | 'gnomish-launch: cannot exec /x/git: No such file or directory\n' || false
    }
}
