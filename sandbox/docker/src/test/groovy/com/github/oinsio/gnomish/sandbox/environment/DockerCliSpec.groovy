package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.charset.StandardCharsets
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
 * by a fake {@code docker} binary, so no daemon is required.
 */
class DockerCliSpec extends Specification {

    @TempDir
    Path tempDir

    private DockerCli cliBackedBy(String script) {
        new DockerCli(fakeBinary(script))
    }

    private DockerCli cliBackedBy(String script, Duration commandTimeout) {
        new DockerCli(fakeBinary(script), commandTimeout)
    }

    private String fakeBinary(String script) {
        FakeDockerBinary.write(tempDir, script)
    }

    private static String readFully(InputStream stream) {
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    }

    def "FR3: run captures stdout, stderr and a zero exit code separately"() {
        given:
        def cli = cliBackedBy('echo "out:$1"; echo "warn" 1>&2; exit 0')

        when:
        def result = cli.run(['network', 'create'])

        then:
        result.exitCode() == 0
        result.ok()
        result.stdout().trim() == 'out:network'
        result.stderr().trim() == 'warn'
    }

    def "FR3: a non-zero exit is returned, not thrown"() {
        given:
        def cli = cliBackedBy('echo "boom" 1>&2; exit 7')

        when:
        def result = cli.run(['volume', 'rm', 'gone'])

        then:
        noExceptionThrown()
        result.exitCode() == 7
        !result.ok()
    }

    def "NFR-R1: a daemon that reports itself unreachable is an infrastructure outage"() {
        given:
        def cli = cliBackedBy('echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock" 1>&2; exit 1')

        when:
        cli.run(['ps'])

        then:
        thrown(DockerUnavailableException)
    }

    @Timeout(30)
    def "FR6 of bound-subprocess-commands: a killed command's partial stderr cannot testify the daemon unreachable"() {
        given: 'a fake docker that cries daemon-unreachable and then hangs past the deadline'
        // Two seconds, not milliseconds: the first exec of a freshly written script can take
        // hundreds of milliseconds (macOS scans new executables), and the echo must land in the
        // capture before the kill for the spec to exercise the classification at all.
        def cli = cliBackedBy(
                'echo "Cannot connect to the Docker daemon at unix:///var/run/docker.sock" 1>&2; sleep 600',
                Duration.ofSeconds(2))

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
        def cli = cliBackedBy('echo OUT; echo ERR 1>&2; exit 0')

        when: 'stderr is merged'
        def merged = cli.start(['exec', 'box', 'cmd'], true)
        def mergedOut = readFully(merged.inputStream)
        merged.waitFor()

        then:
        mergedOut.contains('OUT')
        mergedOut.contains('ERR')

        when: 'stderr is kept separate'
        def split = cli.start(['exec', 'box', 'cmd'], false)
        def splitOut = readFully(split.inputStream)
        split.waitFor()

        then:
        splitOut.contains('OUT')
        !splitOut.contains('ERR')
    }

    def "FR4: start of a missing docker binary throws ProcessStartException"() {
        given:
        def cli = new DockerCli(tempDir.resolve('nope').toString())

        when:
        cli.start(['exec', 'box', 'cmd'], false)

        then:
        thrown(ProcessStartException)
    }
}
