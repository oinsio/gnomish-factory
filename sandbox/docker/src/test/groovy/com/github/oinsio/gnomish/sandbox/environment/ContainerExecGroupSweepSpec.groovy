package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.CaptureRunner
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, FR22, design D19 of fix-terminal-receipt-convergence: the {@code docker} client of a box
 * command is a host process like any other. {@link ContainerTaskExecutionEnvironment#exec} starts
 * it through {@link DockerCli#start}, which hands it to the launch owner its capture runner holds —
 * here a recording {@link ProcessLaunch} returning a {@link FakeProcess} that names a real {@link
 * GroupLeader} as its group — and the handle's wait sweeps that host group. The host sends nothing
 * into the box: after the exec no further docker command (a {@code kill}, an {@code exec} of one)
 * is issued, so what the box runs is ended by the box's own PID namespace, not by the host.
 * The build's half — no second implementation of the launch owner — is {@code LaunchOwnerGateSpec}.
 */
class ContainerExecGroupSweepSpec extends Specification {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '2g', 512L, '10g')

    @TempDir
    Path work

    def clock = new VirtualClock()
    def harvester = { String container, String branch -> } as ContainerHarvest

    // Scenario: lifecycle/subprocess-supervision / The docker client of a box command is a host process
    def "FR21, FR22: a box command's docker client is started by the launch owner and its host group swept, with nothing sent into the box"() {
        given: 'a docker client that will exit cleanly, naming as its group a real one the spec started'
        Process survivor = GroupLeader.start(work)
        FakeProcess client = FakeProcess.running().leading(survivor.pid())
        List<List<String>> launched = []
        ProcessLaunch owner = { ProcessBuilder builder, StdinSource stdin ->
            launched << builder.command()
            client
        } as ProcessLaunch
        LaunchingDockerCli docker = new LaunchingDockerCli(new CaptureRunner(new ProcessSupervisor(), owner))
        ContainerTaskExecutionEnvironment box = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(docker, 'gnomish/img', LIMITS, clock),
                'k1', new BoxGitLink(Path.of('/factory/clone'), harvester), ChildEnvAllowlist.none(), 'proj-1')
        box.materialize('task/x', null)

        when: 'a command runs inside the box, and its client exits'
        ExecHandle handle = box.exec(new ExecCommand(['true'], [:], null, StderrPolicy.PIPED))
        int managementBefore = docker.runs.size()
        client.exit(0)
        int exit = handle.waitForExit()

        then: 'the client was the launch owner\'s, a docker exec on the host'
        launched.size() == 1
        launched[0][0] == 'docker'
        launched[0][1] == 'exec'

        and: 'its host group was swept like any other, and the command\'s result is unchanged'
        survivor.waitFor(10, TimeUnit.SECONDS)
        exit == 0

        and: 'nothing was sent into the box: no docker command followed the exec'
        docker.runs.size() == managementBefore

        cleanup:
        survivor?.destroyForcibly()
    }

    /** A CLI whose management calls answer as a fresh box's, while {@code start} is the real one. */
    static final class LaunchingDockerCli extends DockerCli {
        final List<List<String>> runs = []

        LaunchingDockerCli(CaptureRunner runner) {
            super('docker', Duration.ofSeconds(30), runner)
        }

        @Override
        DockerResult run(List<String> args) {
            runs << args
            def declared = RecordingDockerCli.answerDeclaredVolumes(args)
            if (declared != null) {
                return declared
            }
            def probe = RecordingDockerCli.answerBoxGitVersion(args)
            if (probe != null) {
                return probe
            }
            args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', '')
        }
    }
}
