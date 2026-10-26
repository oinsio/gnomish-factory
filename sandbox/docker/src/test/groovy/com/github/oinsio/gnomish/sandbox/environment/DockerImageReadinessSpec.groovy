package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import java.time.Duration
import spock.lang.Specification

/**
 * FR13, NFR-R3 of add-sandbox-base-image (design D17): the startup assessment's image half runs the
 * checks a fresh box runs — one image inspection, the contract judged from it, then the box's git
 * floor — with no box. The floor probe's helper carries the fixed helper key and the daemon's
 * ownership labels; an unreachable runtime is the typed runtime refusal, as on materialize. The
 * runtime's answers are scripted through the recording docker fake.
 *
 * <p>FR1, FR4, NFR-R1 of add-doctor-command (design D1): the contract inspection alone is the
 * first half of the assessment, one image inspection and no container, and the assessment refuses
 * on the contract exactly when the inspection does.
 */
class DockerImageReadinessSpec extends Specification {

    static final String IMAGE = 'gnomish/img:1'
    static final ObjectOwnership OWNERSHIP = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')

    def docker = new RecordingDockerCli()
    def readiness = new DockerImageReadiness(docker, OWNERSHIP, ImageContractCheck.current())

    def setup() {
        docker.onRun = { List<String> args ->
            RecordingDockerCli.answerDeclaredVolumes(args) ?: DockerResult.of(0, '', '')
        }
    }

    def "FR13: an image at the contract and the floor passes with one inspection and one labelled probe"() {
        when:
        readiness.assess(IMAGE)

        then:
        docker.runs == [
            DockerCommands.inspectImageConfig(IMAGE),
            new BoxGitFloor(docker, DockerImageReadiness.HELPER_KEY, OWNERSHIP).versionProbe(IMAGE)
        ]
        docker.runs[1].containsAll(FactoryDockerLabels.ownershipLabelArgs(DockerImageReadiness.HELPER_KEY, OWNERSHIP))
        docker.starts.isEmpty()
    }

    def "FR13: an image without the contract label is refused before the floor probe starts a container"() {
        given:
        docker.onRun = { List<String> args ->
            DockerResult.of(0, '{"Volumes":null,"Labels":null}', '')
        }

        when:
        readiness.assess(IMAGE)

        then:
        thrown(ImageContractRefusedException)
        docker.runs == [
            DockerCommands.inspectImageConfig(IMAGE)
        ]
    }

    def "FR13: an image below the floor is the floor's refusal"() {
        given:
        docker.boxGitVersion = 'git version 2.43.0'

        when:
        readiness.assess(IMAGE)

        then:
        thrown(BoxGitVersionRefusedException)
    }

    def "NFR-R2: a runtime the #step could not reach is the typed runtime refusal, its cause attached"() {
        given:
        def down = new DockerUnavailableException('daemon unreachable', null as Throwable)
        docker.boxGitVersion = null
        docker.onRun = { List<String> args ->
            if (step == 'probe' && args == DockerCommands.inspectImageConfig(IMAGE)) {
                return RecordingDockerCli.answerDeclaredVolumes(args)
            }
            throw down
        }

        when:
        readiness.assess(IMAGE)

        then:
        def refusal = thrown(ContainerRuntimeUnavailableException)
        refusal.cause.is(down)
        refusal.message.contains(IMAGE)
        refusal.message.contains('daemon unreachable')

        where:
        step << ['inspection', 'probe']
    }

    def "the installation constructor builds over the real docker binary"() {
        expect:
        new DockerImageReadiness(Duration.ofSeconds(1), OWNERSHIP) != null
    }

    def "NFR-R1 of add-doctor-command: the contract inspection is one image inspection and starts no container"() {
        when:
        readiness.inspectContract(IMAGE)

        then:
        docker.runs == [
            DockerCommands.inspectImageConfig(IMAGE)
        ]
        docker.starts.isEmpty()
    }

    def "FR4 of add-doctor-command: the assessment refuses on the contract exactly when the inspection does: #label"() {
        given: 'two readiness adapters over one scripted answer, the floor at its version'
        def answer = { List<String> args ->
            DockerResult.of(0, '{"Volumes":null,"Labels":' + labels + '}', '')
        }
        def inspected = new RecordingDockerCli(onRun: answer)
        def assessed = new RecordingDockerCli(onRun: answer)

        when:
        def inspectionRefused = refusesContract {
            new DockerImageReadiness(inspected, OWNERSHIP, ImageContractCheck.current()).inspectContract(IMAGE)
        }
        def assessmentRefused = refusesContract {
            new DockerImageReadiness(assessed, OWNERSHIP, ImageContractCheck.current()).assess(IMAGE)
        }

        then:
        inspectionRefused == refused
        assessmentRefused == refused
        inspected.runs == [
            DockerCommands.inspectImageConfig(IMAGE)
        ]

        where:
        label | labels || refused
        'the current contract' | '{"' + ImageContract.LABEL + '":"' + ImageContract.current().contractVersion() + '"}' || false
        'another contract' | '{"' + ImageContract.LABEL + '":"' + (ImageContract.current().contractVersion() + 1) + '"}' || true
        'no contract label' | '{"other":"1"}' || true
        'no labels at all' | 'null' || true
    }

    def "NFR-R2: a runtime the contract inspection could not reach is the typed runtime refusal"() {
        given:
        def down = new DockerUnavailableException('daemon unreachable', null as Throwable)
        docker.onRun = { List<String> args -> throw down }

        when:
        readiness.inspectContract(IMAGE)

        then:
        def refusal = thrown(ContainerRuntimeUnavailableException)
        refusal.cause.is(down)
        refusal.message.contains(IMAGE)
    }

    def "FR1, NFR-R1 of add-doctor-command: the presence inspection is one image inspection, judges no label and starts no container"() {
        given: 'an image held locally whose labels name no contract'
        docker.onRun = { List<String> args ->
            DockerResult.of(0, '{"Volumes":null,"Labels":{"other":"1"}}', '')
        }

        when:
        readiness.inspectPresence(IMAGE)

        then:
        noExceptionThrown()
        docker.runs == [
            DockerCommands.inspectImageConfig(IMAGE)
        ]
        docker.starts.isEmpty()
    }

    def "FR1 of add-doctor-command: an image the runtime does not hold fails the presence inspection, naming it"() {
        given:
        docker.onRun = { List<String> args ->
            DockerResult.of(1, '', 'Error: No such image: ' + IMAGE)
        }

        when:
        readiness.inspectPresence(IMAGE)

        then:
        def failure = thrown(DockerCommandFailedException)
        failure.message.contains(IMAGE)
        failure.message.contains('No such image')
    }

    def "NFR-R2: a runtime the presence inspection could not reach is the typed runtime refusal"() {
        given:
        def down = new DockerUnavailableException('daemon unreachable', null as Throwable)
        docker.onRun = { List<String> args -> throw down }

        when:
        readiness.inspectPresence(IMAGE)

        then:
        def refusal = thrown(ContainerRuntimeUnavailableException)
        refusal.cause.is(down)
        refusal.message.contains(IMAGE)
    }

    def "the inspection's installation constructor builds over the real docker binary"() {
        expect:
        new DockerImageContractInspection(Duration.ofSeconds(1)) != null
    }

    private static boolean refusesContract(Closure<?> check) {
        try {
            check.call()
            false
        } catch (ImageContractRefusedException ignored) {
            true
        }
    }
}
