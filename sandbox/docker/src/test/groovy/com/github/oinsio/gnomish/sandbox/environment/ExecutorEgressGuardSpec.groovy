package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of define-executor-contract (design D10, the egress allowlist union): every box guard of a
 * task admits the operator's egress allowlist and the hosts the task's law grants its {@code
 * program} executors, observed in the configuration the guard renders. Daemon-free: the guard
 * renders its configuration before it inspects anything, and the self-check that follows needs a
 * running box, so whatever it throws is set aside.
 */
class ExecutorEgressGuardSpec extends Specification implements ContainerEnvironmentsFixture {

    static final String KEY = 'org-repo-9'

    @TempDir
    Path guardRoot

    def setup() {
        sandbox = new SandboxProperties('gnomish/img', null, null, null, ['registry.example.com'], null, false,
        null, null, null, null)
        docker.onRun = { List<String> args ->
            def declared = RecordingDockerCli.answerDeclaredVolumes(args, '{"/cache":{}}')
            declared != null ? declared : (args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', ''))
        }
    }

    def "FR10: the #role guard admits the operator's hosts, then the executors' granted hosts"() {
        given:
        def environments = new ContainerEnvironments(
                new BoxEquipment(docker, sandbox, timing, guardRoot, OwnershipMode.TRACKED, ImageContractCheck.current()),
                KEY, new BoxGitLink(Path.of('/factory/clone'), harvester), ChildEnvAllowlist.none(), 'proj-1',
                { -> DenialRestoration.none() }, ['api.acme.example'])

        when:
        try {
            pick(environments).materialize('gnomish/task-9', 'a' * 40)
        } catch (RuntimeException ignored) {
            // the self-check after the guard is up needs a running box; not this spec's subject
        }

        then:
        Files.readString(guardRoot.resolve(KEY + suffix).resolve('allowlist.json')) ==
                '["registry.example.com","api.acme.example"]'

        where:
        role | suffix | pick
        'round' | '' | { ContainerEnvironments e ->
            e.roundEnvironment()
        }
        'verification' | '-v' | { ContainerEnvironments e ->
            e.verificationEnvironment()
        }
    }
}
