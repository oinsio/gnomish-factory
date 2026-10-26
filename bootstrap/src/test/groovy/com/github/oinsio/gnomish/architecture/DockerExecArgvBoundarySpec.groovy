package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * NFR-S1, NFR-S4, FR6, M2, M3 of fix-docker-exec-env-argv: no environment value reaches a
 * {@code docker exec} argv, and the form that once put one there cannot be built again.
 *
 * <p>{@code DockerCommands} is the one source that spells the environment flag, and it renders
 * {@code -e NAME} only, the value travelling in the client's own environment. A second source
 * spelling {@code "-e"} would be a second argv composer with no such guarantee. A process is
 * constructed only by the docker client seam ({@code DockerCli}) and the host adapter ({@code
 * HostTaskExecutionEnvironment}); the host adapter's site is FR6's evidence that it is unchanged
 * (it clears the child's environment and never had the flag). The docker start seam has one entry,
 * taking a {@code DockerExecLaunch}: no overload accepts a bare argv, so the bare form is
 * unconstructible rather than discouraged.
 *
 * <p>The scan covers every production source under {@code sandbox/}, comments stripped, and asserts
 * it reached the tree and each allowlisted file, so a moved tree or a renamed owner fails loudly
 * instead of passing over nothing. {@code :bootstrap} owns it for the reason {@link
 * ClaimlessGitBoundarySpec} gives: it is the one module that sees every layer at once. The
 * process-construction half is the narrow form of {@link ProcessSpawnBoundarySpec}, the gate
 * {@code add-subprocess-access-log} FR13 later widens into one whole-tree spawn boundary.
 */
class DockerExecArgvBoundarySpec extends Specification {

    private static final String SANDBOX_TREE = 'sandbox/'

    private static final String ENVIRONMENT = 'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/'

    /** The one argv composer allowed to spell the environment flag. */
    private static final String FLAG_OWNER = ENVIRONMENT + 'DockerCommands.java'

    /** The only sources under the sandbox tree that construct a process. */
    private static final List<String> PROCESS_CONSTRUCTORS = [
        ENVIRONMENT + 'DockerCli.java',
        ENVIRONMENT + 'HostTaskExecutionEnvironment.java',
    ]

    /** Below this the scan cannot have reached the sandbox tree (108 sources at the time of writing). */
    private static final int KNOWN_SANDBOX_SOURCES = 80

    private static final String ENVIRONMENT_FLAG = '"-e"'

    private static final String PROCESS_CONSTRUCTION = 'new ProcessBuilder'

    // Scenario: sandbox/execution-environment / The bare form is unconstructible
    def "NFR-S1, FR6, M2: one source spells the environment flag and two construct a process under sandbox/"() {
        given: 'every production source of the sandbox tree, as the compiler sees it'
        def code = RepoSourceTree.productionSources {
            it.startsWith(SANDBOX_TREE)
        }.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }

        def paths = code.keySet()
        def flagSpellers = code.findAll { path, text ->
            text.contains(ENVIRONMENT_FLAG)
        }.keySet()
        def constructors = code.findAll { path, text ->
            text.contains(PROCESS_CONSTRUCTION)
        }.keySet()

        expect: 'the scan reached the tree and every allowlisted file'
        paths.size() >= KNOWN_SANDBOX_SOURCES
        ([FLAG_OWNER]+ PROCESS_CONSTRUCTORS).every { paths.contains(it) }

        and: 'exactly one source spells the environment flag: the argv composer'
        flagSpellers == [FLAG_OWNER] as Set

        and: 'only the docker client seam and the host adapter construct a process'
        constructors == PROCESS_CONSTRUCTORS as Set
    }

    // Scenario: sandbox/execution-environment / The bare form is unconstructible
    def "NFR-S4, M3: the docker start seam exposes no entry taking a bare argv"() {
        given: 'the docker client seam and the launch value it takes'
        def cli = Class.forName('com.github.oinsio.gnomish.sandbox.environment.DockerCli')
        def launch = Class.forName('com.github.oinsio.gnomish.sandbox.environment.DockerExecLaunch')

        when:
        def starts = cli.declaredMethods.findAll { it.name == 'start' }

        then: 'one start, and it takes the launch first, never a list of words'
        starts.size() == 1
        starts[0].parameterTypes[0] == launch
        !starts[0].parameterTypes.any {
            List.isAssignableFrom(it) || it == String[]
        }
    }
}
