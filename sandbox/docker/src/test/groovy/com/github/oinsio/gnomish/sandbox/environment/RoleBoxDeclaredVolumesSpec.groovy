package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR1, FR4 of fix-image-declared-volumes: the fresh judge box ({@code <key>-j}) and the fresh
 * verification box ({@code <key>-v}) are built through the same {@link ContainerEnvironments} seam
 * as the round box, so the image's declared volumes are read for them too and every declared path
 * the factory does not mount itself becomes an ephemeral override — none is left to an anonymous
 * volume. Daemon-free: the recording docker answers the image inspect and refuses the container
 * inspect (no box survives), and the self-check that follows the box's creation is not the subject,
 * so whatever it throws is set aside.
 */
class RoleBoxDeclaredVolumesSpec extends Specification implements ContainerEnvironmentsFixture {

    static final String KEY = 'org-repo-7'
    static final List<String> CACHE_OVERRIDE = new DeclaredVolumeOverrides(['/cache']).argv()

    def setup() {
        docker.onRun = { List<String> args ->
            RecordingDockerCli.answerDeclaredVolumes(args, '{"/cache":{},"/gnomish/work":{}}')
            ?: (args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', ''))
        }
    }

    /** The {@code run} argv that started the named task container. */
    private List<String> runOf(String containerName) {
        docker.runs.find { it[0] == 'run' && it.contains(containerName) }
    }

    private static boolean carries(List<String> argv, List<String> fragment) {
        Collections.indexOfSubList(argv, fragment) >= 0
    }

    // Scenario: sandbox/execution-environment / Judge and verification boxes are covered by the same rule
    def "FR4: the #role box carries the same ephemeral override as the round box"() {
        given:
        def environments = environments(KEY)

        when: 'the round box and the role box are materialized from an image declaring /cache and the working copy'
        materialize(environments.roundEnvironment())
        materialize(pick(environments))

        then: 'both task containers carry the override for /cache, and none for the explicitly mounted working copy'
        def round = runOf(FactoryDockerLabels.containerName(KEY))
        def box = runOf(FactoryDockerLabels.containerName(KEY + suffix))
        round != null && box != null
        carries(round, CACHE_OVERRIDE)
        carries(box, CACHE_OVERRIDE)
        !carries(box, new DeclaredVolumeOverrides(['/gnomish/work']).argv())

        and: 'every declared path is accounted for: the image inspect ran for the role box before its run'
        docker.runs.indexOf(DockerCommands.inspectImageVolumes(sandbox.image())) <docker.runs.indexOf(box)

        where:
        role | suffix | pick
        'judge' | '-j' | { ContainerEnvironments e ->
            e.judgeEnvironment()
        }
        'verification' | '-v' | { ContainerEnvironments e ->
            e.verificationEnvironment()
        }
    }

    private static void materialize(def environment) {
        try {
            environment.materialize('gnomish/task-7', 'a' * 40)
        } catch (RuntimeException ignored) {
            // the self-check after the box's creation needs a running box; not this spec's subject
        }
    }
}
