package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code TestDockerEnvironment}'s composition (FR13–FR15, NFR-C3,
 * design D14 of fix-terminal-receipt-convergence), branch by branch, on the inner build of
 * {@link MiniDockerProject}: the operating system is a parameter, never this machine's, and the
 * {@code docker} it resolves a context through is a committed stand-in preset. The forked process
 * is {@code env} — the owner composes any {@code ProcessForkOptions} alike — so the verdict is what
 * a forked process observed. {@link TestDockerConventionsFunctionalSpec} covers the same owner reached through
 * {@code test-conventions}, in a real test JVM and in PIT's minions.
 */
class TestDockerEnvironmentFunctionalSpec extends Specification {

    static final String MAC = 'Mac OS X'
    static final String LINUX = 'Linux'

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniDockerProject mini

    def setup() {
        mini = new MiniDockerProject(projectDir, home)
    }

    // Scenario: build-system/quality-gates / An operator's DOCKER_HOST is handed on unchanged
    def "FR14, NFR-C3: an operator's DOCKER_HOST reaches the forked process as it is, and no docker process runs"() {
        given:
        Path docker = mini.contextAnswering('unix-socket')

        when:
        mini.run(LINUX, docker, [DOCKER_HOST: 'tcp://operator.invalid:2375'])

        then:
        mini.observed().DOCKER_HOST == 'tcp://operator.invalid:2375'
        !Files.exists(MiniDockerProject.log(docker))
    }

    // Scenario: build-system/quality-gates / Without DOCKER_HOST the current context's endpoint is handed on
    def "FR14, NFR-C3: without DOCKER_HOST the context's endpoint is handed on, resolved once under the operator's context"() {
        given:
        Path docker = mini.contextAnswering('unix-socket')

        when: 'two tasks are composed in one build, with the operator on a context of their own'
        mini.run(LINUX, docker, [DOCKER_CONTEXT: 'operator-context'], 'probe', 'probeAgain')

        then:
        mini.observed('probe').DOCKER_HOST == 'unix:///run/stand-in/docker.sock'
        mini.observed('probeAgain').DOCKER_HOST == 'unix:///run/stand-in/docker.sock'
        !mini.observed('probe').containsKey('DOCKER_CONTEXT')

        and: 'one docker process for the build, run under the operator\'s context'
        MiniDockerProject.records(docker).size() == 1
        MiniDockerProject.records(docker)[0].contains('argv=context inspect --format ' + DockerContextEndpoint.FORMAT)
        MiniDockerProject.records(docker)[0].contains('DOCKER_CONTEXT=[operator-context]')
    }

    // Scenario: build-system/quality-gates / On macOS Testcontainers mounts the daemon's own socket path
    def "FR14: on macOS with a unix:// endpoint the socket override names the VM's socket"() {
        when:
        mini.run(MAC, mini.contextAnswering('unix-socket'), [:])

        then:
        mini.observed().TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE == TestDockerEnvironment.VM_SOCKET
    }

    def "FR14: on macOS an override the operator set is left as it is"() {
        when:
        mini.run(MAC, mini.contextAnswering('unix-socket'), [TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: '/operator/docker.sock'])

        then:
        mini.observed().TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE == '/operator/docker.sock'
    }

    // Scenario: build-system/quality-gates / On Linux no socket override is set
    def "FR14: on Linux no socket override is set"() {
        when:
        mini.run(LINUX, mini.contextAnswering('unix-socket'), [:])

        then:
        !mini.observed().containsKey('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')
    }

    // Scenario: build-system/quality-gates / The operator's CLI plugins are still found
    def "FR13, FR15: the copy is the committed configuration, naming the operator's cli-plugins directory"() {
        given: 'an operator configuration with a credential helper and a plugin directory'
        Path operator = Files.createDirectories(home.resolve('operator-docker').resolve('cli-plugins')).parent
        operator.resolve('config.json').text = '{"credsStore": "desktop"}\n'

        when:
        mini.run(LINUX, mini.contextAnswering('unix-socket'), [DOCKER_CONFIG: operator.toString()])

        then:
        String copy = mini.observed().DOCKER_CONFIG
        copy != operator.toString()
        Map config = new groovy.json.JsonSlurper().parse(new File(copy, 'config.json')) as Map
        config == [cliPluginsExtraDirs: [operator.resolve('cli-plugins').toString()]]
    }

    def "FR15: without an operator plugin directory the copy is the committed configuration alone"() {
        when:
        mini.run(LINUX, mini.contextAnswering('unix-socket'), [DOCKER_CONFIG: Files.createDirectories(home.resolve('bare')).toString()])

        then:
        new groovy.json.JsonSlurper().parse(new File(mini.observed().DOCKER_CONFIG, 'config.json')) == [:]
    }

    // Scenario: build-system/quality-gates / An endpoint the build cannot hand on fails with the remedy
    def "FR14: a context endpoint that one variable cannot carry (#answer) fails the build with the variables to set"() {
        when:
        BuildResult result = mini.runner(LINUX, mini.contextAnswering(answer), [:]).buildAndFail()

        then:
        ['DOCKER_HOST', 'DOCKER_CERT_PATH', 'DOCKER_TLS_VERIFY'].every { result.output.contains(it) }

        where:
        answer << ['ssh-host', 'tls-context']
    }

    // Scenario: build-system/quality-gates / A machine without Docker still builds
    def "FR14: with #machine the build sets no endpoint and the task runs"() {
        given:
        Path docker = preset == null ? home.resolve('no-such-docker') : mini.presetLink(preset)

        when:
        BuildResult result = mini.run(LINUX, docker, [:])

        then:
        result.task(':probe').outcome == SUCCESS
        !mini.observed().containsKey('DOCKER_HOST')
        !mini.observed().containsKey('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')
        mini.observed().containsKey('DOCKER_CONFIG')

        where:
        machine                        | preset
        'no docker binary'             | null
        'a context that fails inspect' | 'docker-daemon-down'
    }

    // D14 as amended 2026-10-18 (task 7.5): the daemon resolves a bare name on the PATH it started with
    def "FR14: a bare docker resolves on the client's PATH, never on the PATH the daemon started with"() {
        given: 'a docker only on the client\'s PATH, in a directory no daemon was started with'
        Path docker = mini.contextAnswering('docker', 'client-bin')

        when:
        mini.run(LINUX, 'docker', [PATH: "${docker.parent}:${System.getenv('PATH')}".toString()])

        then:
        mini.observed().DOCKER_HOST == 'unix:///run/stand-in/path-docker.sock'
        MiniDockerProject.records(docker).size() == 1
    }

    def "FR14: a docker the client's PATH does not hold sets no endpoint, with a warning naming the PATH searched"() {
        given:
        String path = Files.createDirectories(home.resolve('empty-bin')).toString()

        when:
        BuildResult result = mini.run(LINUX, 'docker', [PATH: path])

        then:
        result.task(':probe').outcome == SUCCESS
        !mini.observed().containsKey('DOCKER_HOST')
        result.output.contains("docker was not found on the build's PATH (${path})")
    }
}
