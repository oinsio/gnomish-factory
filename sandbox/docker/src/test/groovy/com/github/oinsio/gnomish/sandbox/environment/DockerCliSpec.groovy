package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR3, FR4, NFR-R1 of add-sandbox-core: the docker subprocess seam captures
 * stdout/stderr/exit separately, returns a non-zero exit without throwing,
 * classifies a binary that cannot launch and a daemon that reports itself
 * unreachable as an infrastructure outage ({@link DockerUnavailableException}),
 * and streams {@code docker exec} output with optional stderr merge — all driven
 * by a fake {@code docker} binary — a committed stand-in preset (ADR 0015) — so no daemon is
 * required.
 */
class DockerCliSpec extends Specification {

    @TempDir
    Path tempDir

    /** The stdout and stderr lines of the docker-speaks-both scenario, read from its data rather than retyped. */
    private static final String OUT = StandIn.data('stdout-docker#out-network').trim()
    private static final String ERR = StandIn.data('stderr#warn').trim()

    private DockerCli cliBackedBy(String preset) {
        new DockerCli(StandIn.docker(preset).toString())
    }

    private DockerCli cliBackedBy(String preset, Duration commandTimeout) {
        new DockerCli(StandIn.docker(preset).toString(), commandTimeout)
    }

    private static String readFully(InputStream stream) {
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    }

    def "FR3: run captures stdout, stderr and a zero exit code separately"() {
        given:
        def cli = cliBackedBy('docker-speaks-both')

        when:
        def result = cli.run(['network', 'create'])

        then:
        result.exitCode() == 0
        result.ok()
        result.stdout().forParsing().trim() == 'out:network'
        result.stderr().forParsing().trim() == 'warn'
    }

    def "FR3: a non-zero exit is returned, not thrown"() {
        given:
        def cli = cliBackedBy('docker-exit-7')

        when:
        def result = cli.run(['volume', 'rm', 'gone'])

        then:
        noExceptionThrown()
        result.exitCode() == 7
        !result.ok()
    }

    def "NFR-R1: a daemon that reports itself unreachable is an infrastructure outage"() {
        given:
        def cli = cliBackedBy('docker-daemon-down')

        when:
        cli.run(['ps'])

        then:
        thrown(DockerUnavailableException)
    }

    @Timeout(30)
    def "FR6 of bound-subprocess-commands: a killed command's partial stderr cannot testify the daemon unreachable"() {
        given: 'a fake docker that cries daemon-unreachable and then hangs past the deadline'
        // Two seconds, not milliseconds: the first exec of the library's script can take hundreds
        // of milliseconds (macOS assesses it once per checkout), and the echo must land in the
        // capture before the kill for the spec to exercise the classification at all.
        def cli = cliBackedBy('docker-daemon-down-then-stall', Duration.ofSeconds(2))

        when:
        def result = cli.run(['ps'])

        then: 'the timeout is the outcome; only a command that ran to completion classifies an outage'
        noExceptionThrown()
        result.termination() == Termination.TIMED_OUT

        and: 'the captured tail still carries what the command said before the kill'
        result.stderr().contains('Cannot connect to the Docker daemon')
    }

    def "NFR-R1: a docker binary that cannot be launched is an infrastructure outage"() {
        given:
        def cli = new DockerCli(tempDir.resolve('does-not-exist').toString())

        when:
        cli.run(['ps'])

        then:
        thrown(DockerUnavailableException)
    }

    def "FR4: start streams exec output, merging stderr only when asked"() {
        given:
        def cli = cliBackedBy('docker-speaks-both')

        when: 'stderr is merged'
        def merged = cli.start(['exec', 'box', 'cmd'], StderrPolicy.MERGED, StdinSource.NONE)
        def mergedOut = readFully(merged.inputStream)
        merged.waitFor()

        then:
        mergedOut.contains(OUT)
        mergedOut.contains(ERR)

        when: 'stderr is kept separate'
        def split = cli.start(['exec', 'box', 'cmd'], StderrPolicy.PIPED, StdinSource.NONE)
        def splitOut = readFully(split.inputStream)
        split.waitFor()

        then:
        splitOut.contains(OUT)
        !splitOut.contains(ERR)
    }

    // FR20, D18 of fix-terminal-receipt-convergence: the exec client's stderr — which carries the
    //     box process's — is redirected on the host side into the file the round named
    def "FR20: start writes the client's stderr to the named host file, and only its stdout to the stream"() {
        given:
        def cli = cliBackedBy('docker-speaks-both')
        def file = tempDir.resolve('round-stderr')

        when:
        def started = cli.start(['exec', 'box', 'cmd'], StderrPolicy.toFile(file), StdinSource.NONE)
        def out = readFully(started.inputStream)
        started.waitFor()

        then:
        out.contains(OUT)
        !out.contains(ERR)
        Files.readString(file).contains(ERR)
    }

    def "FR4: start of a missing docker binary throws ProcessStartException"() {
        given:
        def cli = new DockerCli(tempDir.resolve('nope').toString())

        when:
        cli.start(['exec', 'box', 'cmd'], StderrPolicy.PIPED, StdinSource.NONE)

        then:
        thrown(ProcessStartException)
    }

    // FR23, D20 of fix-terminal-receipt-convergence: the exec client is started by the launch owner
    //     the capture runner holds, with the caller's input source, so no pipe is left to pump into
    def "FR23: start hands the exec builder and its input source to the capture runner's launch"() {
        given:
        List<ProcessBuilder> builders = []
        List<StdinSource> sources = []
        def process = new FakeProcess()
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            builders << builder
            sources << stdin
            process
        } as ProcessLaunch
        def cli = new DockerCli('docker', Duration.ofSeconds(30), new CaptureRunner(new ProcessSupervisor(), launch))
        def input = StdinSource.of('content'.bytes)

        when:
        def started = cli.start(['exec', '-i', 'box', 'cat'], StderrPolicy.MERGED, input)

        then:
        started.is(process)
        builders*.command() == [
            [
                'docker',
                'exec',
                '-i',
                'box',
                'cat'
            ]
        ]
        builders[0].redirectErrorStream()
        sources == [input]
    }

    // D17: the supervisor a caller of start waits under is the one the management commands run under
    def "start's caller waits under the capture runner's own supervisor"() {
        given:
        def supervisor = new ProcessSupervisor(Duration.ofMillis(300))

        expect:
        new DockerCli('docker', Duration.ofSeconds(30), new CaptureRunner(supervisor, ProcessLaunch.grouped()))
                .supervisor().is(supervisor)
    }
}
