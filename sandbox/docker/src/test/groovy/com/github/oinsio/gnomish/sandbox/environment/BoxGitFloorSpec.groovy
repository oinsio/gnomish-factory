package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification

/**
 * F122, F135, NFR-S2 of tighten-spec-contract (design D13, amended 2026-10-13): the box's seed
 * clone reads the reftable factory clone with the sandbox image's own git, so an image whose git
 * is below the floor is refused before the seed clone — before any network, volume or container
 * exists — with one coded ERROR and a reason naming the image and the remedy. The image's answer
 * is scripted through the recording docker fake; no old image is built.
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
    def "F122: an image at or above the floor passes silently: #line"() {
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
            'git version 2.51.0',
            'git version 2.53.0'
        ]
    }

    // Scenario: sandbox/execution-environment / An image whose git is below the floor is refused before the seed clone
    def "F135, NFR-S2: an image below the floor is refused with the remedy and one coded ERROR: #line"() {
        given:
        docker.boxGitVersion = line

        when:
        floor.require(IMAGE)

        then: 'the refusal names the image, the floor, what the image printed, why and what to do'
        def refused = thrown(BoxGitVersionRefusedException)
        refused.message.contains(IMAGE)
        refused.message.contains(GitVersion.FLOOR.toString())
        refused.message.contains(line)
        refused.message.contains(BoxGitFloor.REASON)
        refused.message.contains('docs/examples/sandbox-image')

        and:
        logs.list.size() == 1
        logs.list[0].level == Level.ERROR
        logs.list[0].formattedMessage.startsWith(OperatorEvent.BOX_GIT_VERSION_REFUSED.head())
        logs.list[0].formattedMessage.contains(KEY)
        logs.list[0].formattedMessage.contains(IMAGE)

        where:
        line << [
            'git version 2.43.0',
            'git version 2.45.1',
            'git version 2.50.1',
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
        logs.list*.level == [Level.ERROR]
    }

    def "F135: a fresh materialize on an old image creates nothing — the probe is its only docker run after the inspect"() {
        given:
        docker.boxGitVersion = 'git version 2.43.0'
        docker.onRun = { List<String> args ->
            args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', '')
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
            floor.versionProbe(IMAGE)
        ]
    }
}
