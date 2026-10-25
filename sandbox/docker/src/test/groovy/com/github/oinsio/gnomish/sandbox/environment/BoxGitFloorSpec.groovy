package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification

/**
 * F122, F135, NFR-S2 of tighten-spec-contract (design D13, amended 2026-10-13): the box's seed
 * clone reads the reftable factory clone with the sandbox image's own git, so an image whose git
 * is below the floor is refused before the seed clone — before any network, volume or container
 * exists — with a reason naming the image and the remedy. The image's answer is scripted through
 * the recording docker fake; no old image is built. FR11 of add-sandbox-base-image (design D9): the
 * floor is the factory's one floor, {@link GitVersion#FLOOR}; a git the box's former floor (2.51.0)
 * admitted is now refused. Design D19 of add-sandbox-base-image: the check logs nothing — the coded
 * line is the deciding layer's — and an unreachable runtime is never cited as the image's refusal.
 */
class BoxGitFloorSpec extends Specification {

    static final String KEY = 'org-repo-7'
    static final String IMAGE = 'gnomish/img'
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')

    def docker = new RecordingDockerCli()
    def floor = new BoxGitFloor(docker, KEY, OWNERSHIP)

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(BoxGitFloor, Level.INFO)
    }

    def cleanup() {
        logs.detach()
    }

    def "F122: the probe is the image's own git, off the network, carrying the environment's ownership labels"() {
        expect:
        floor.versionProbe(IMAGE) == ['run', '--rm'] + FactoryDockerLabels.ownershipLabelArgs(KEY, OWNERSHIP) +
        [
            '--network',
            'none',
            IMAGE,
            'git',
            '--version'
        ]
    }

    // Scenario: sandbox/execution-environment / An image at or above the floor seeds as before
    def "F122, FR11: an image at or above the factory's one floor passes silently: #line"() {
        given:
        docker.boxGitVersion = line

        when:
        floor.require(IMAGE)

        then:
        noExceptionThrown()
        docker.runs == [floor.versionProbe(IMAGE)]
        logs.list.isEmpty()

        where:
        line << [
            'git version 2.56.0',
            'git version 2.56.1 (Apple Git-200)',
            'git version 2.57.0',
            'git version 3.0.0'
        ]
    }

    // Scenario: sandbox/execution-environment / An image whose git is below the floor is refused before the seed clone
    def "F135, NFR-S2: an image below the floor is refused with the remedy, and the check writes no line: #line"() {
        given:
        docker.boxGitVersion = line

        when:
        floor.require(IMAGE)

        then: 'the refusal names the image, the floor, what the image printed, why and what to do'
        def refused = thrown(BoxGitVersionRefusedException)
        refused.message.contains(IMAGE)
        refused.message.contains("git ${GitVersion.FLOOR} or newer")
        refused.message.contains(line)
        refused.message.contains(BoxGitFloor.REASON)
        BoxGitFloor.REASON.endsWith(GitVersion.FLOOR_REASON)
        refused.message.contains('FROM the sandbox base image')
        refused.message.contains('docs/examples/sandbox-image')

        and: 'D19: the refusal is the message; the command, the startup or the condition writes the line'
        logs.list.isEmpty()

        where:
        line << [
            'git version 2.43.0',
            'git version 2.51.0',
            'git version 2.55.2',
            'not a version line'
        ]
    }

    // Scenario: sandbox/execution-environment / An image without git is refused with the runtime's complaint
    def "F135: an image without git is refused quoting the runtime's own complaint"() {
        given:
        docker.boxGitVersion = null
        docker.onRun = { List<String> args ->
            DockerResult.of(127, '', 'exec: "git": executable file not found in $PATH')
        }

        when:
        floor.require(IMAGE)

        then:
        def refused = thrown(BoxGitVersionRefusedException)
        refused.message.contains('executable file not found')
        logs.list.isEmpty()
    }

    // Scenario: sandbox/execution-environment / A runtime that cannot start the helper is not a version refusal
    def "D19, NFR-R2: a runtime that cannot run the probe propagates as the runtime's outage, never as a version refusal"() {
        given:
        docker.boxGitVersion = null
        docker.onRun = { List<String> args ->
            throw new DockerUnavailableException('docker daemon is unreachable', null as Throwable)
        }

        when:
        floor.require(IMAGE)

        then: "the runtime's own signal leaves the check unchanged, not a version refusal"
        thrown(DockerUnavailableException)

        and: 'nothing was logged as a refusal'
        logs.list.isEmpty()
    }

    def "F135: a fresh materialize on an old image creates nothing — the probe is its only docker run after the inspections"() {
        given:
        docker.boxGitVersion = 'git version 2.43.0'
        docker.onRun = { List<String> args ->
            RecordingDockerCli.answerDeclaredVolumes(args)
            ?: (args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', ''))
        }
        def env = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(docker, IMAGE, new ResourceLimits('2', '2g', 512L, '10g'), new VirtualClock(), OWNERSHIP.mode()),
                KEY, new BoxGitLink(Path.of('/factory/clone'), { String c, String b -> } as ContainerHarvest),
                ChildEnvAllowlist.none(), OWNERSHIP.projectId())

        when:
        env.materialize('gnomish/task-x', null)

        then:
        thrown(BoxGitVersionRefusedException)
        docker.runs == [
            DockerCommands.inspectContainerState(FactoryDockerLabels.containerName(KEY)),
            // the image's one inspection precedes the probe (design D3 of add-sandbox-base-image)
            DockerCommands.inspectImageConfig(IMAGE),
            floor.versionProbe(IMAGE)
        ]
    }
}
