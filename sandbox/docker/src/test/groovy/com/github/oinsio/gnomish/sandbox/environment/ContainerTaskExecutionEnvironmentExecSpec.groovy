package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR3, FR24 of add-sandbox-core: {@code ContainerTaskExecutionEnvironment#exec}
 * and {@code #readFile} against a live scripted {@link Process} (rather than the
 * daemon-free {@link RecordingDockerCli}, whose {@code start()} always throws) —
 * the interactive flag actually reaching the exec argv in both directions, the
 * prompt handed to the launch owner as the client's input source (or none, design
 * D20 of fix-terminal-receipt-convergence), and
 * {@code readFile} carrying the channel's real bytes through rather than
 * silently swallowing them to empty.
 */
class ContainerTaskExecutionEnvironmentExecSpec extends Specification {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')

    def clock = new VirtualClock()
    def harvester = { String container, String branch -> } as ContainerHarvest

    private ContainerTaskExecutionEnvironment env(DockerCli docker) {
        new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(docker, 'gnomish/img', LIMITS, clock),
                'k1', new BoxGitLink(Path.of('/factory/clone'), harvester), ChildEnvAllowlist.none(), 'proj-1')
    }

    def "FR24: exec passes -i and hands the stdin bytes to the launch as the client's input"() {
        given:
        def process = new ScriptedProcess()
        def docker = new ScriptedDockerCli(process)
        def e = env(docker)
        e.materialize('task/x', null)

        when:
        e.exec(new ExecCommand(['cat'], [:], 'hello-stdin', StderrPolicy.PIPED))

        then: 'the argv is interactive'
        docker.starts.last().contains('-i')

        and: 'the prompt is the source the launch owner turns into the client\'s input file'
        docker.sources.last().bytes() == 'hello-stdin'.getBytes(StandardCharsets.UTF_8)
    }

    def "FR24: exec omits -i and hands no input when the command carries none"() {
        given:
        def process = new ScriptedProcess()
        def docker = new ScriptedDockerCli(process)
        def e = env(docker)
        e.materialize('task/x', null)

        when:
        e.exec(new ExecCommand(['true'], [:], null, StderrPolicy.PIPED))

        then: 'the argv is not interactive'
        !docker.starts.last().contains('-i')

        and: 'the client reads nothing: the launch owner gives it /dev/null'
        docker.sources.last().is(StdinSource.NONE)
    }

    // FR20 of fix-terminal-receipt-convergence (D18): the exec client's stderr is redirected on
    //     the host side, so the policy the round chose reaches the client's launch unchanged
    def "FR20: exec hands the command's stderr policy to the exec client's launch"() {
        given:
        def docker = new ScriptedDockerCli(new ScriptedProcess())
        def e = env(docker)
        e.materialize('task/x', null)
        def stderr = StderrPolicy.toFile(Path.of('/tmp/round/stderr'))

        when:
        e.exec(new ExecCommand(['claude'], [:], 'prompt', stderr))

        then:
        docker.stderrs.last() == stderr
    }

    def "readFile carries the channel's real content through, never swallowed to empty"() {
        given:
        def process = new ScriptedProcess('file-body'.getBytes(StandardCharsets.UTF_8))
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
