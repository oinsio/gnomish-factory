package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 as amended 2026-10-17, twice, of fix-terminal-receipt-convergence: before the
 * launcher is in front, the launch owner resolves the command's executable as {@code execvp} would
 * and follows its {@code #!} interpreter chain as the kernel's {@code binfmt_script} does, so what
 * made {@code start()} throw before the launcher existed — a missing binary, a missing interpreter at
 * any hop — still throws, naming the command, before any process exists.
 *
 * <p>The owner is handed a recording launcher ({@code launcher-record}) in place of the platform's:
 * its log exists exactly when a process was started, so "no child pid was ever minted" is its
 * absence. The interpreter chains are committed fakes under the library's {@code process/}
 * directory, their interpreters named relative to it, so the child's working directory is that
 * directory, as the kernel would open them.
 */
class ExecutableLookupSpec extends Specification implements FakeBinaries {

    @TempDir
    Path inputs

    @TempDir
    Path work

    Path launcher

    def setup() {
        launcher = StandIn.link(work.resolve('setsid'), 'launcher-record')
    }

    // Scenario: lifecycle/subprocess-supervision / A missing binary fails the launch before any process starts
    def "FR21: a binary that cannot run fails the launch naming it, before any process exists: #shape"() {
        given:
        Files.writeString(work.resolve('not-executable'), 'data')
        Files.createDirectory(work.resolve('a-directory'))
        String name = binary.replace('@work', work.toString())

        when:
        launch(onPath("${work.resolve('nowhere')}", name), StdinSource.of(new byte[1]))

        then: 'the JDK\'s own exception type, naming the binary'
        IOException refused = thrown()
        refused.message.contains(name)

        and: 'no child pid was ever minted, and no input file is left'
        !started()
        Files.list(inputs).withCloseable { it.toList() } == []

        where:
        shape | binary
        'an absent path' | '/nonexistent/gnomish-no-such-binary'
        'a file without the x bit' | '@work/not-executable'
        'a directory' | '@work/a-directory'
        'a bare name on no PATH' | 'gnomish-no-such-binary'
    }

    // Scenario: lifecycle/subprocess-supervision / A missing binary fails the launch before any process starts
    def "FR21: a bare name present only in the working directory is not found: PATH '#path'"() {
        given: 'the binary sits in the child\'s working directory, and nowhere on its PATH'
        Path cwd = Files.createDirectory(work.resolve('cwd'))
        StandIn.link(cwd.resolve('gnomish-here-only'), 'quick')

        when:
        launch(onPath(path, 'gnomish-here-only').directory(cwd.toFile()), StdinSource.NONE)

        then:
        IOException refused = thrown()
        refused.message.contains('gnomish-here-only')
        !started()

        where:
        path << [
            '/nonexistent',
            '',
            '.',
            ':/nonexistent',
            'cwd'
        ]
    }

    // Scenario: lifecycle/subprocess-supervision / A script whose interpreter is missing fails the launch before any process starts
    def "FR21: a script whose interpreter is missing at its #hop interpreter fails the launch before any process exists"() {
        given:
        Path script = fakeBinary(fake)

        when:
        launch(inChain(script), StdinSource.NONE)

        then: 'the failure a missing binary raises, naming the command and the hop that did not resolve'
        IOException refused = thrown()
        refused.message.contains(script.toString())
        refused.message.contains('/nonexistent/gnomish-interpreter')
        !started()

        where:
        hop | fake
        'first' | 'missing-interpreter'
        'second' | 'hop-4'
        'fourth' | 'hop-2'
    }

    // Scenario: lifecycle/subprocess-supervision / A script whose interpreter is missing fails the launch before any process starts
    def "FR21: an interpreter that exists is not judged further: #shape"() {
        given:
        Path script = fakeBinary(fake)

        when:
        launch(inChain(script), StdinSource.NONE)

        then: 'the launch went ahead, and what follows is the command\'s own'
        started()
        Files.readString(StandIn.log(launcher)).contains("argv=${script}")

        where:
        shape | fake
        'a chain past four hops, which the kernel judges itself' | 'hop-1'
        'env naming a program found nowhere' | 'env-missing'
        'an interpreter line naming nothing' | 'empty-interpreter'
    }

    def "FR21: an executable the factory cannot read is not judged by its head"() {
        given: 'a copy of a fake whose interpreter is missing, which the factory may execute but not read'
        Path copy = Files.copy(fakeBinary('missing-interpreter'), work.resolve('execute-only'))
        Files.setPosixFilePermissions(copy, [
            PosixFilePermission.OWNER_EXECUTE
        ] as Set)

        when:
        launch(inChain(copy), StdinSource.NONE)

        then:
        started()
    }

    // Scenario: lifecycle/subprocess-supervision / A script whose interpreter is missing fails the launch before any process starts
    def "FR21: env's own 127 for a program found nowhere is the command's result, not a launch failure"() {
        given:
        ProcessBuilder builder = inChain(fakeBinary('env-missing'))

        when:
        Captured captured = new CaptureRunner(new ProcessSupervisor(), ProcessLaunch.grouped(inputs))
                .run(builder, Duration.ofSeconds(30))

        then:
        captured.termination() == Termination.EXITED
        captured.exitCode() == 127
        captured.stderr().contains('gnomish-no-such-interpreter')
    }

    // Scenario: lifecycle/subprocess-supervision / A script whose interpreter is missing fails the launch before any process starts
    def "FR21: a waiter on the platform's launcher sees the missing interpreter as the launch exception"() {
        given:
        ProcessBuilder builder = new ProcessBuilder(fakeBinary('missing-interpreter').toString())
        TestChildEnvironment.cleared(builder)

        when:
        new CaptureRunner(new ProcessSupervisor(), ProcessLaunch.grouped(inputs)).run(builder, Duration.ofSeconds(30))

        then: 'the launch exception, naming the binary — the classification the caller had without a launcher'
        IOException refused = thrown()
        refused.message.contains('missing-interpreter')
    }

    private void launch(ProcessBuilder builder, StdinSource stdin) {
        new GroupedLaunch(inputs, 'Linux', work.toString()).start(builder, stdin).waitFor()
    }

    /** {@code script} run from the library's {@code process/} directory, where its chain's interpreters live. */
    private ProcessBuilder inChain(Path script) {
        onPath('/nonexistent', script.toString()).directory(StandIn.library().resolve('process').toFile())
    }

    private boolean started() {
        Files.exists(StandIn.log(launcher))
    }
}
