package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Design D3 of add-sandbox-base-image (FR5, NFR-R1): the identity behind {@link ImageFacts} being
 * the one read of an image — a fresh environment inspects each of its two images exactly once,
 * the sandbox image when its box is created and the guard image when its guard is, whatever the
 * consumers of the facts. Driven through the {@link ContainerEnvironments} seam for every role, so
 * a consumer that asked the runtime again, or a path that read the facts twice, counts here.
 * Daemon-free: no box or guard exists yet, every create succeeds, a guard reports running once it
 * was run, and the self-check that follows is not the subject, so whatever it throws is set aside.
 */
class ImageInspectionCountSpec extends Specification implements ContainerEnvironmentsFixture {

    static final String KEY = 'org-repo-7'

    /** Where the guards' configuration renders: the guard writes it before it inspects anything. */
    @TempDir
    Path guardRoot

    /** The keys of the guards this fake daemon has run. */
    final Set<String> guardsRun = [] as Set

    def setup() {
        docker.onRun = { List<String> args ->
            def declared = RecordingDockerCli.answerDeclaredVolumes(args, '{"/cache":{}}')
            if (declared != null) {
                return declared
            }
            def guardKey = [KEY, KEY + '-j', KEY + '-v'].find {
                args == GuardCommands.inspectGuardRunning(it)
            }
            if (guardKey != null) {
                return guardsRun.contains(guardKey) ? DockerResult.of(0, 'true\n', '') : DockerResult.of(1, '', 'No such object')
            }
            def ran = [KEY, KEY + '-j', KEY + '-v'].find {
                args[0] == 'run' && args.contains(FactoryDockerLabels.guardName(it))
            }
            if (ran != null) {
                guardsRun << ran
            }
            args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', '')
        }
    }

    private ContainerEnvironments environmentsFor(String key) {
        new ContainerEnvironments(
                new BoxEquipment(docker, sandbox, timing, guardRoot, OwnershipMode.TRACKED, ImageContractCheck.current()),
                key, new BoxGitLink(Path.of('/factory/clone'), harvester), ChildEnvAllowlist.none(), 'proj-1',
                { -> DenialRestoration.none() })
    }

    private int inspections(String image) {
        docker.runs.count { it == DockerCommands.inspectImageConfig(image) }
    }

    def "FR5: a fresh #role environment inspects the sandbox image once and the guard image once"() {
        given:
        def environment = pick(environmentsFor(KEY))

        when:
        materialize(environment)

        then: 'its guard was created, so both images were needed'
        guardsRun == [KEY + suffix] as Set

        and: 'one inspection per image'
        inspections(sandbox.image()) == 1
        inspections(sandbox.guardImage()) == 1

        where:
        role | suffix | pick
        'round' | '' | { ContainerEnvironments e ->
            e.roundEnvironment()
        }
        'judge' | '-j' | { ContainerEnvironments e ->
            e.judgeEnvironment()
        }
        'verification' | '-v' | { ContainerEnvironments e ->
            e.verificationEnvironment()
        }
    }

    def "FR5: a guard that does not come up is recreated from the facts its first creation read"() {
        given: 'a guard that never reports running'
        def environment = environmentsFor(KEY).roundEnvironment()
        def script = docker.onRun
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning(KEY) ? DockerResult.of(1, '', 'No such object') : script.call(args)
        }

        when:
        materialize(environment)

        then: 'the guard was run twice, from one inspection of its image'
        docker.runs.count {
            it[0] == 'run' && it.contains(FactoryDockerLabels.guardName(KEY))
        } == 2
        inspections(sandbox.guardImage()) == 1
    }

    private static void materialize(def environment) {
        try {
            environment.materialize('gnomish/task-7', 'a' * 40)
        } catch (RuntimeException ignored) {
            // the self-check after the guard is up needs a running box; not this spec's subject
        }
    }
}
