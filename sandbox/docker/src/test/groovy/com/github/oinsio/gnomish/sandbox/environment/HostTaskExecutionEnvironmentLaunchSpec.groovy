package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR18, FR23 and design D16, D20 of fix-terminal-receipt-convergence: the host adapter starts every
 * {@code exec} child through the {@link ProcessLaunch} it was constructed with, converting the
 * command's stdin once into the {@link StdinSource} the owner applies — the prompt as a file, or
 * nothing — so no pump thread of its own writes into the child's pipe.
 */
class HostTaskExecutionEnvironmentLaunchSpec extends Specification {

    @TempDir
    Path workingCopy

    List<ProcessBuilder> builders = []
    List<StdinSource> sources = []
    def process = new FakeProcess()

    private HostTaskExecutionEnvironment hostEnv(ProcessLaunch launch) {
        new HostTaskExecutionEnvironment(workingCopy, new VirtualClock(), TestChildEnvironment.hostAllowlist(), launch)
    }

    private ProcessLaunch recording() {
        { ProcessBuilder builder, StdinSource stdin ->
            builders << builder
            sources << stdin
            process
        } as ProcessLaunch
    }

    def "FR23: the command's stdin reaches the launch owner as the whole input, in UTF-8"() {
        when:
        hostEnv(recording()).exec(new ExecCommand(['cat'], [:], 'prompt — ü', StderrPolicy.MERGED))

        then: 'the owner started the builder the adapter composed, with the prompt as its source'
        builders*.command() == [['cat']]
        builders[0].directory() == workingCopy.toFile()
        builders[0].redirectErrorStream()
        sources.size() == 1
        sources[0].bytes() == 'prompt — ü'.getBytes(StandardCharsets.UTF_8)
    }

    def "FR23: a command without stdin is launched with no input at all"() {
        when:
        hostEnv(recording()).exec(new ExecCommand(['true'], [:], null, StderrPolicy.PIPED))

        then:
        sources == [StdinSource.NONE]
        !builders[0].redirectErrorStream()
    }

    def "FR20: a command whose stderr goes to a file is launched with its stderr redirected to that file"() {
        given:
        def file = workingCopy.resolveSibling('round-scratch').resolve('stderr')

        when:
        hostEnv(recording()).exec(new ExecCommand(['true'], [:], null, StderrPolicy.toFile(file)))

        then:
        !builders[0].redirectErrorStream()
        builders[0].redirectError().file() == file.toFile()
    }

    def "FR4: a launch the owner refuses surfaces as ProcessStartException naming the binary"() {
        given:
        def refusing = { ProcessBuilder builder, StdinSource stdin ->
            throw new IOException('no such binary')
        } as ProcessLaunch

        when:
        hostEnv(refusing).exec(ExecCommand.of(['missing-binary']))

        then:
        def e = thrown(ProcessStartException)
        e.message.contains('missing-binary')
        e.cause instanceof IOException
    }
}
