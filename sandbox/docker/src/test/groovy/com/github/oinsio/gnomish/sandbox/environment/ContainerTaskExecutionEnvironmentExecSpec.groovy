package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import spock.lang.Specification

/**
 * FR3, FR24 of add-sandbox-core: {@code ContainerTaskExecutionEnvironment#exec}
 * and {@code #readFile} against a live scripted {@link Process} (rather than the
 * daemon-free {@link RecordingDockerCli}, whose {@code start()} always throws) —
 * the interactive flag actually reaching the exec argv in both directions, {@code
 * ChildProcessStdin.feed} really delivering (or skipping) the child's stdin, and
 * {@code readFile} carrying the channel's real bytes through rather than
 * silently swallowing them to empty.
 */
class ContainerTaskExecutionEnvironmentExecSpec extends Specification {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')

    def clock = { -> Instant.now() } as Clock
    def harvester = { String container, String branch -> } as ContainerHarvest

    private ContainerTaskExecutionEnvironment env(DockerCli docker) {
        new ContainerTaskExecutionEnvironment(
                docker, 'k1', Path.of('/factory/clone'), harvester, 'gnomish/img', 'runc', LIMITS, false, clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
    }

    def "FR24: exec passes -i and ChildProcessStdin.feed really delivers the stdin bytes to the child"() {
        given:
        def process = new ScriptedProcess()
        def docker = new ScriptedDockerCli(process)
        def e = env(docker)
        e.materialize('task/x', null)

        when:
        e.exec(new ExecCommand(['cat'], [:], 'hello-stdin', false))

        then: 'the argv is interactive'
        docker.starts.last().contains('-i')

        and: 'the child actually received and saw the end of the piped bytes — feed() really ran'
        process.stdin.closed.await(2, TimeUnit.SECONDS)
        process.stdin.toByteArray() == 'hello-stdin'.getBytes(StandardCharsets.UTF_8)
    }

    def "FR24: exec omits -i and never touches the child's stdin when the command carries none"() {
        given:
        def process = new ScriptedProcess()
        def docker = new ScriptedDockerCli(process)
        def e = env(docker)
        e.materialize('task/x', null)

        when:
        e.exec(new ExecCommand(['true'], [:], null, false))

        then: 'the argv is not interactive'
        !docker.starts.last().contains('-i')

        and: 'no writer ever closes the pipe — a null stdin never spawns a feed() writer'
        !process.stdin.closed.await(200, TimeUnit.MILLISECONDS)
    }

    def "readFile carries the channel's real content through, never swallowed to empty"() {
        given:
        def process = new ScriptedProcess(stdout: 'file-body'.getBytes(StandardCharsets.UTF_8))
        def docker = new ScriptedDockerCli(process)
        def e = env(docker)
        e.materialize('task/x', null)

        when:
        def result = e.readFile('note.txt', 100)

        then:
        result.isPresent()
        new String(result.get(), StandardCharsets.UTF_8) == 'file-body'
    }
}
