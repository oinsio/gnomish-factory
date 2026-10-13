package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * FR1 of add-sandbox-core (design D1): a working copy is prepared one way only — {@code
 * materialize} on the {@link TaskExecutionEnvironment} port — and the port has no way to turn a
 * gnome-touched environment into a reusable image.
 *
 * <ul>
 *   <li>The port's whole surface is the lifecycle, the exec seam, the file channel, the passport
 *       the denial surface and the guard's liveness probe (D14 of tighten-spec-contract); no operation persists the environment, and the docker command
 *       owners spell none of the runtime's image-making subcommands.
 *   <li>The production callers of {@code .materialize(} are exactly the fresh environments' owners
 *       and the decorators that pass the call on, and the in-box preparation itself — the seed
 *       clone — is issued from one place, reached only through the container adapter's
 *       {@code materialize}.
 * </ul>
 *
 * <p>Every allowlisted file must still match, so a rename cannot leave the gate naming nothing.
 */
class EnvironmentPreparationOwnerSpec extends Specification {

    private static final String ENV = 'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/'

    /** The port's whole surface (D1): no snapshot, commit, save or export operation among it. */
    static final Set<String> PORT_SURFACE = [
        'materialize',
        'exec',
        'putFile',
        'readFile',
        'harvest',
        'dispose',
        'scratchRoot',
        'passport',
        'readDenials',
        'denialCursor',
        'restoreDenials',
        'probeGuard'
    ] as Set

    /** The production files allowed to call {@code .materialize(}, each with why. */
    static final Map<String, String> MATERIALIZE_CALLERS = [
        ('adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/FreshJudgeEnvironments.java'): 'the sandboxed judge box, pinned at the attempt commit',
        ('adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/SandboxCheckEnvironmentSource.java'): 'fresh-box verification, pinned at the attempt commit',
        ('adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/HostCheckEnvironmentSource.java') : 'the host check environment over the adopted workspace',
        (ENV + 'EnvironmentLease.java') : 'the round box — and the box a --discard-work resume rebuilds after its disposal',
        (ENV + 'SelfCheckedEnvironment.java') : 'decorator: delegates, then self-checks',
        ('sandbox/core/src/main/java/com/github/oinsio/gnomish/sandbox/LiveBox.java') : 'the live-box mechanism hands each build to its role\'s materializer',
    ]

    private static Map<String, String> productionCode() {
        def sources = RepoSourceTree.productionSources { String path ->
            !path.startsWith('test-fixtures/')
        }
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    private static List<String> namers(Map<String, String> code, String pattern) {
        code.findAll { path, text -> text =~ pattern }.keySet().sort()
    }

    // Scenario: sandbox/execution-environment / No snapshot of a gnome-touched environment
    def "D1: no port operation persists an environment, and no docker owner spells an image-making subcommand"() {
        expect: 'the port declares exactly its lifecycle, exec, channel, passport, denial and guard-probe operations'
        def declared = TaskExecutionEnvironment.declaredMethods.findAll {
            !Modifier.isStatic(it.modifiers) && !it.synthetic
        }
        declared*.name as Set == PORT_SURFACE

        and: 'none of them hands back anything but a handle, bytes, a path, a passport or a denial value'
        declared*.returnType*.simpleName.every {
            it in [
                'void',
                'ExecHandle',
                'Optional',
                'String',
                'CapabilityPassport',
                'DenialRead'
            ]
        }

        when: 'the docker command owners are scanned, comments stripped'
        def docker = productionCode().findAll { path, text ->
            path.startsWith('sandbox/docker/src/main/')
        }

        then: 'the scan reached them, and no argv element is commit, save, export or checkpoint'
        docker.keySet().contains(ENV + 'DockerCommands.java')
        namers(docker, /"(commit|save|export|checkpoint)"/) == []
    }

    // Scenario: sandbox/execution-environment / One materialize behind every fresh environment
    def "D1: fresh-box verification, the judge box and the --discard-work rebuild prepare their working copy only through materialize"() {
        given:
        def code = productionCode()

        expect: 'the callers of materialize are exactly the allowlisted owners and pass-throughs'
        namers(code, /\.materialize\s*\(/) == MATERIALIZE_CALLERS.keySet().sort()

        and: 'the in-box preparation — the seed clone — is issued only by the container materializer'
        namers(code, /DockerCommands\s*\.\s*seedClone\s*\(/) == [
            ENV + 'ContainerMaterializer.java'
        ]
        namers(code, /DockerSeedCloneCommand\s*\.\s*seedClone\s*\(/) == [ENV + 'DockerCommands.java']

        and: 'and that materializer is built only by the container adapter, whose materialize is its one entry'
        namers(code, /new\s+ContainerMaterializer\s*\(/) == [
            ENV + 'ContainerTaskExecutionEnvironment.java'
        ]
    }
}
