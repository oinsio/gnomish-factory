package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.BaseTag
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR5, NFR-R1, NFR-O1, UX2 of add-sandbox-base-image (design D3): a fresh materialize refuses a
 * sandbox image whose image-contract label is missing or names another version, from the one
 * inspection it already makes — before the git probe, and with nothing created. The factory under
 * test requires contract 2 and carries the {@code 0.3} base tag (release 0.3.1), which the fix names. The
 * daemon is the recording fake, answering the image's {@code Config} with the labels each feature
 * gives it.
 */
class BoxImageContractSpec extends Specification {

    static final String KEY = 'org-repo-7'
    static final String IMAGE = 'gnomish/img'
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')
    static final ImageContract REQUIRES_TWO =
    new ImageContract(2, ImageContract.current().guardAlias(), ImageContract.current().guardPort())

    def docker = new RecordingDockerCli()

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(ImageContractCheck, Level.INFO)
    }

    def cleanup() {
        logs.detach()
    }

    /** The fake daemon: no box exists yet, every create succeeds, the image carries {@code labels}. */
    private void imageLabelled(String labels) {
        docker.boxGitVersion = RecordingDockerCli.AT_FLOOR
        docker.onRun = { List<String> args ->
            if (args == DockerCommands.inspectImageConfig(IMAGE)) {
                return DockerResult.of(0, '{"Volumes":null,"Labels":' + labels + '}', '')
            }
            args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', '')
        }
    }

    private ContainerTaskExecutionEnvironment box() {
        def fixture = BoxEquipmentFixture.forBox(
                docker, IMAGE, new ResourceLimits('2', '2g', 512L, '10g'), new VirtualClock(), OWNERSHIP.mode())
        def equipment = new BoxEquipment(fixture.docker(), fixture.sandbox(), fixture.timing(),
                fixture.guardConfigRoot(), fixture.ownershipMode(),
                new ImageContractCheck(REQUIRES_TWO, Optional.of(new BaseTag('0.3'))))
        new ContainerTaskExecutionEnvironment(equipment, KEY,
                new BoxGitLink(Path.of('/factory/clone'), { String c, String b -> } as ContainerHarvest),
                ChildEnvAllowlist.none(), OWNERSHIP.projectId())
    }

    /** Everything the materialize asked the daemon: the container-state probe and the one inspect. */
    private static List<List<String>> refusedRuns() {
        [
            DockerCommands.inspectContainerState(FactoryDockerLabels.containerName(KEY)),
            DockerCommands.inspectImageConfig(IMAGE)
        ]
    }

    // Scenario: sandbox/execution-environment / Stale image refused with the fix
    def "FR5, UX2: an image built for contract 1 is refused before anything exists, naming both versions and the base tag"() {
        given:
        imageLabelled('{"io.gnomish.image-contract":"1"}')

        when:
        box().materialize('gnomish/task-x', null)

        then: 'the refusal names the image, the found and the required version, and the FROM line'
        def refused = thrown(ImageContractRefusedException)
        refused.message.contains('"' + IMAGE + '"')
        refused.message.contains(ImageContract.LABEL + '=1')
        refused.message.contains('needs 2')
        refused.message.endsWith('FROM ghcr.io/oinsio/gnomish-sandbox-base:0.3')

        and: 'UX2: it fits on two terminal lines'
        refused.message.length() <= 160

        and: 'no box: no network, volume, probe, seed or container — only the inspection that read the label'
        docker.runs == refusedRuns()

        and: 'D19: the check writes no line; the layer that decides on the refusal does'
        logs.list.isEmpty()
    }

    // Scenario: sandbox/execution-environment / Image not built on the base
    def "FR5, NFR-O1: an image without the contract label is refused as not built on the base: #labels"() {
        given:
        imageLabelled(labels)

        when:
        box().materialize('gnomish/task-x', null)

        then: 'the refusal says the image is not built on the base, what it lacks and how to fix it'
        def refused = thrown(ImageContractRefusedException)
        refused.message.contains('"' + IMAGE + '" is not built on the base')
        refused.message.contains('no ' + ImageContract.LABEL + '=2 label')
        refused.message.endsWith('FROM ghcr.io/oinsio/gnomish-sandbox-base:0.3')
        refused.message.length() <= 160

        and: 'nothing was created'
        docker.runs == refusedRuns()

        and: "D19: no line of the check's own"
        logs.list.isEmpty()

        where:
        labels << [
            'null',
            '{}',
            '{"org.opencontainers.image.version":"2"}'
        ]
    }

    // Scenario: sandbox/execution-environment / Matching image starts normally
    def "FR5, NFR-R1: an image carrying the required version gets its box from the one inspection"() {
        given:
        imageLabelled('{"io.gnomish.image-contract":"2"}')

        when:
        box().materialize('gnomish/task-x', null)

        then: 'the box was created'
        docker.runs.any {
            it[0] == 'run' && it.contains(FactoryDockerLabels.containerName(KEY))
        }

        and: 'the sandbox image was inspected once: the labels came from the read of its declared volumes'
        docker.runs.count {
            it == DockerCommands.inspectImageConfig(IMAGE)
        } == 1
        docker.runs.count { it[0] == 'image' } == 1

        and: 'the check logged nothing'
        logs.list.isEmpty()
    }

    def "FR5: a reattach to a surviving box neither inspects the image nor checks its contract"() {
        given: 'a running box, and an image that would be refused'
        imageLabelled('{}')
        docker.onRun = { List<String> args ->
            args[0] == 'inspect' ? DockerResult.of(0, 'true\n', '') : DockerResult.of(0, '', '')
        }

        when:
        box().materialize('gnomish/task-x', null)

        then:
        noExceptionThrown()
        !docker.runs.any { it[0] == 'image' }
        logs.list.isEmpty()
    }
}
