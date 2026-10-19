package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 (as amended 2026-10-17, twice) of fix-terminal-receipt-convergence: the launch
 * owner starts every host process through the platform's launcher — {@code setsid} on Linux, the
 * OS's own {@code /usr/bin/perl} calling {@code POSIX::setsid} on macOS — so the child leads a
 * process group whose id is its pid, the launcher hands it the resolved executable and adds nothing
 * of its own to a successful run. What the owner refuses before any process exists is pinned by
 * {@code ExecutableLookupSpec}; the launcher's refusal line and its classification by {@code
 * LauncherRefusalSpec}.
 *
 * <p>The children are real processes: what is pinned is what the OS reports about a group and what
 * an {@code exec} does with an argument, neither of which survives a fake. Where a feature needs to
 * know whether any process was started, the owner is handed a recording launcher instead of the
 * platform's ({@code launcher-record}): its log exists exactly when it ran.
 */
class HostProcessLaunchSpec extends Specification implements FakeBinaries {

    @TempDir
    Path inputs

    @TempDir
    Path work

    def "FR21: the child's pid is its process group's id"() {
        given: 'a child that stays alive until it is killed'
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('stall').toString())
        TestChildEnvironment.cleared(builder)

        when:
        Process process = ProcessLaunch.grouped(inputs).start(builder, StdinSource.NONE)

        then: 'once the launcher has run — its new session precedes the exec — the child leads a group of its own'
        eventually('the launcher made the child a group leader') {
            groupOf(process.pid()) == process.pid()
        }
        groupOf(process.pid()) != groupOf(ProcessHandle.current().pid())

        cleanup:
        process?.destroyForcibly()
    }

    def "FR21: the macOS launcher is chosen by os.name and groups the child on whichever host runs it"() {
        given: 'the OS\'s own perl, which macOS ships and the Linux CI runner and spec runner image carry'
        assert Files.isExecutable(Path.of('/usr/bin/perl'))
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('stall').toString())
        TestChildEnvironment.cleared(builder)

        when: 'the owner is told the platform is macOS, so the branch runs on a Linux gate as well'
        Process process = new GroupedLaunch(inputs, 'Mac OS X', System.getenv('PATH')).start(builder, StdinSource.NONE)

        then:
        eventually('the perl launcher made the child a group leader') {
            groupOf(process.pid()) == process.pid()
        }

        cleanup:
        process?.destroyForcibly()
    }

    def "FR21: a one-element argv with shell metacharacters is executed verbatim"() {
        given: 'a binary whose own name holds a command separator, exiting 3'
        Path odd = StandIn.link(work.resolve('quick;echo injected'), 'quick')
        ProcessBuilder builder = new ProcessBuilder(odd.toString())
        TestChildEnvironment.cleared(builder)

        when:
        Process process = ProcessLaunch.grouped(inputs).start(builder, StdinSource.NONE)

        then: 'the binary itself ran — no shell split its name — and the launcher added no output'
        process.waitFor(10, TimeUnit.SECONDS)
        process.exitValue() == 3
        process.inputStream.text == ''
        process.errorStream.text == ''
    }

    def "FR21: the launcher runs the resolved executable, and the caller's builder keeps its command"() {
        given: 'a recording launcher, and a bare name found on the child\'s PATH'
        Path launcher = StandIn.link(work.resolve('setsid'), 'launcher-record')
        Path bin = Files.createDirectory(work.resolve('bin'))
        Path probe = StandIn.link(bin.resolve('gnomish-probe'), 'quick')
        ProcessBuilder builder = onPath("${bin}", 'gnomish-probe', '--flag', 'a b')
        List<String> command = builder.command()

        when:
        Process process = new GroupedLaunch(inputs, 'Linux', work.toString()).start(builder, StdinSource.NONE)
        process.waitFor(10, TimeUnit.SECONDS)

        then: 'the launcher was handed the absolute executable, then the arguments unchanged'
        Files.readString(StandIn.log(launcher)).contains("argv=${probe} --flag a b")

        and: 'the builder the caller configured is the one it handed in'
        builder.command().is(command)
        command == [
            'gnomish-probe',
            '--flag',
            'a b'
        ]
    }

    def "FR21: a bare name falls back to the JVM's PATH when the child's environment names none"() {
        given:
        Path launcher = StandIn.link(work.resolve('setsid'), 'launcher-record')
        Path probe = StandIn.link(work.resolve('gnomish-probe'), 'quick')
        ProcessBuilder builder = new ProcessBuilder('gnomish-probe')
        builder.environment().clear()

        when:
        new GroupedLaunch(inputs, 'Linux', work.toString()).start(builder, StdinSource.NONE).waitFor(10, TimeUnit.SECONDS)

        then:
        Files.readString(StandIn.log(launcher)).contains("argv=${probe}")
    }

    // Scenario: lifecycle/subprocess-supervision / A missing launcher fails the invocation
    def "FR21: a missing launcher helper fails the invocation naming it: #shape"() {
        given:
        Path mark = work.resolve('started')
        ProcessBuilder builder = new ProcessBuilder('touch', mark.toString())
        TestChildEnvironment.cleared(builder)

        when:
        new GroupedLaunch(inputs, osName, work.resolve('empty').toString()).start(builder, StdinSource.of(new byte[1]))

        then: 'the failure names what is missing, and the command never ran ungrouped'
        IOException refused = thrown()
        refused.message.contains(named)
        !Files.exists(mark)
        Files.list(inputs).withCloseable { it.toList() } == []

        where:
        shape | osName || named
        'no setsid on the JVM\'s PATH' | 'Linux' || 'setsid'
        'a platform with no launcher' | 'Windows 11' || 'Windows 11'
    }

    // Scenario: lifecycle/subprocess-supervision / A missing launcher fails the invocation
    def "FR21: a host with no kill for the group sweep starts nothing, naming it"() {
        given: 'the platform\'s own launcher, and a sweep signaller that does not exist'
        Path mark = work.resolve('started')
        ProcessBuilder builder = new ProcessBuilder('touch', mark.toString())
        TestChildEnvironment.cleared(builder)
        Path noKill = work.resolve('no-kill')
        HostLauncher host = new HostLauncher(System.getProperty('os.name'), System.getenv('PATH'), noKill)

        when:
        new GroupedLaunch(inputs, System.getenv('PATH'), host).start(builder, StdinSource.NONE)

        then: 'the failure names the missing kill, and the command never ran unswept'
        IOException refused = thrown()
        refused.message.contains(noKill.toString())
        !Files.exists(mark)
    }

    // Scenario: lifecycle/subprocess-supervision / The launcher writes nothing on a successful exec
    def "FR21: a successful launch leaves the launcher's stderr empty, even with perl's warnings forced on"() {
        given: 'a program that writes nothing, and an environment asking perl for every warning'
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('quick').toString())
        TestChildEnvironment.cleared(builder)
        builder.environment().put('PERL5OPT', '-w')

        when:
        Process process = ProcessLaunch.grouped(inputs).start(builder, StdinSource.NONE)

        then: 'the program\'s own exit, and not a byte of the launcher\'s'
        process.waitFor(10, TimeUnit.SECONDS)
        process.exitValue() == 3
        process.errorStream.text == ''
    }

    private static long groupOf(long pid) {
        Process ps = new ProcessBuilder('ps', '-o', 'pgid=', '-p', String.valueOf(pid)).start()
        String out = ps.inputStream.text.trim()
        ps.waitFor(10, TimeUnit.SECONDS)
        Long.parseLong(out)
    }
}
