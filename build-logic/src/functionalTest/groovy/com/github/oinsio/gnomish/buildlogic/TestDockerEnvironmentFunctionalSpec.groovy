package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code TestDockerEnvironment}'s composition (FR13–FR15, NFR-C3,
 * design D14 of fix-terminal-receipt-convergence), branch by branch, on an inner build that hands
 * the owner the host it composes for: the operating system is a parameter, never this machine's,
 * and the {@code docker} it resolves a context through is the committed stand-in preset
 * {@code docker-context-inspect}, whose per-run link is named after the endpoint it answers and
 * records each run. The forked process is {@code env} under two {@code Exec} tasks — the owner
 * composes any {@code ProcessForkOptions} alike — so the verdict is what a forked process
 * observed. {@link TestDockerConventionsFunctionalSpec} covers the same owner reached through
 * {@code test-conventions}, in a real test JVM and in PIT's minions.
 */
class TestDockerEnvironmentFunctionalSpec extends Specification {

    static final String MAC = 'Mac OS X'
    static final String LINUX = 'Linux'

    @TempDir
    Path projectDir
    @TempDir
    Path home

    def setup() {
        GradleRunnerSupport.writeFile(projectDir, 'settings.gradle', "rootProject.name = 'mini-docker-env'\n")
        GradleRunnerSupport.writeFile(projectDir, TestDockerEnvironment.RELATIVE_PATH, '{}\n')
        GradleRunnerSupport.writeFile(projectDir, 'build.gradle', """\
buildscript {
    dependencies {
        classpath files(${GradleRunnerSupport.pluginClasspath().collect { "'" + GradleRunnerSupport.quotedPath(it) + "'" }.join(', ')})
    }
}

import com.github.oinsio.gnomish.buildlogic.TestDockerEnvironment

def host = new TestDockerEnvironment.Host(providers.gradleProperty('os').get(), providers.gradleProperty('docker').get())
['probe', 'probeAgain'].each { name ->
    tasks.register(name, Exec) {
        executable '/usr/bin/env'
        def seen = new ByteArrayOutputStream()
        standardOutput = seen
        doLast { file("\${name}.env").text = seen.toString() }
        TestDockerEnvironment.applyTo(project, it, host)
    }
}
""")
    }

    // Scenario: build-system/quality-gates / An operator's DOCKER_HOST is handed on unchanged
    def "FR14, NFR-C3: an operator's DOCKER_HOST reaches the forked process as it is, and no docker process runs"() {
        given:
        Path docker = contextAnswering('unix-socket')

        when:
        run(LINUX, docker, [DOCKER_HOST: 'tcp://operator.invalid:2375'])

        then:
        observed().DOCKER_HOST == 'tcp://operator.invalid:2375'
        !Files.exists(log(docker))
    }

    // Scenario: build-system/quality-gates / Without DOCKER_HOST the current context's endpoint is handed on
    def "FR14, NFR-C3: without DOCKER_HOST the context's endpoint is handed on, resolved once under the operator's context"() {
        given:
        Path docker = contextAnswering('unix-socket')

        when: 'two tasks are composed in one build, with the operator on a context of their own'
        run(LINUX, docker, [DOCKER_CONTEXT: 'operator-context'], 'probe', 'probeAgain')

        then:
        observed('probe').DOCKER_HOST == 'unix:///run/stand-in/docker.sock'
        observed('probeAgain').DOCKER_HOST == 'unix:///run/stand-in/docker.sock'
        !observed('probe').containsKey('DOCKER_CONTEXT')

        and: 'one docker process for the build, run under the operator\'s context'
        records(docker).size() == 1
        records(docker)[0].contains('argv=context inspect --format ' + DockerContextEndpoint.FORMAT)
        records(docker)[0].contains('DOCKER_CONTEXT=[operator-context]')
    }

    // Scenario: build-system/quality-gates / On macOS Testcontainers mounts the daemon's own socket path
    def "FR14: on macOS with a unix:// endpoint the socket override names the VM's socket"() {
        when:
        run(MAC, contextAnswering('unix-socket'), [:])

        then:
        observed().TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE == TestDockerEnvironment.VM_SOCKET
    }

    def "FR14: on macOS an override the operator set is left as it is"() {
        when:
        run(MAC, contextAnswering('unix-socket'), [TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: '/operator/docker.sock'])

        then:
        observed().TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE == '/operator/docker.sock'
    }

    // Scenario: build-system/quality-gates / On Linux no socket override is set
    def "FR14: on Linux no socket override is set"() {
        when:
        run(LINUX, contextAnswering('unix-socket'), [:])

        then:
        !observed().containsKey('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')
    }

    // Scenario: build-system/quality-gates / The operator's CLI plugins are still found
    def "FR13, FR15: the copy is the committed configuration, naming the operator's cli-plugins directory"() {
        given: 'an operator configuration with a credential helper and a plugin directory'
        Path operator = Files.createDirectories(home.resolve('operator-docker').resolve('cli-plugins')).parent
        operator.resolve('config.json').text = '{"credsStore": "desktop"}\n'

        when:
        run(LINUX, contextAnswering('unix-socket'), [DOCKER_CONFIG: operator.toString()])

        then:
        String copy = observed().DOCKER_CONFIG
        copy != operator.toString()
        Map config = new groovy.json.JsonSlurper().parse(new File(copy, 'config.json')) as Map
        config == [cliPluginsExtraDirs: [operator.resolve('cli-plugins').toString()]]
    }

    def "FR15: without an operator plugin directory the copy is the committed configuration alone"() {
        when:
        run(LINUX, contextAnswering('unix-socket'), [DOCKER_CONFIG: Files.createDirectories(home.resolve('bare')).toString()])

        then:
        new groovy.json.JsonSlurper().parse(new File(observed().DOCKER_CONFIG, 'config.json')) == [:]
    }

    // Scenario: build-system/quality-gates / An endpoint the build cannot hand on fails with the remedy
    def "FR14: a context endpoint that one variable cannot carry (#answer) fails the build with the variables to set"() {
        when:
        BuildResult result = runner(LINUX, contextAnswering(answer), [:]).buildAndFail()

        then:
        ['DOCKER_HOST', 'DOCKER_CERT_PATH', 'DOCKER_TLS_VERIFY'].every { result.output.contains(it) }

        where:
        answer << ['ssh-host', 'tls-context']
    }

    // Scenario: build-system/quality-gates / A machine without Docker still builds
    def "FR14: with #machine the build sets no endpoint and the task runs"() {
        given:
        Path docker = preset == null ? home.resolve('no-such-docker') : presetLink(preset)

        when:
        BuildResult result = run(LINUX, docker, [:])

        then:
        result.task(':probe').outcome == SUCCESS
        !observed().containsKey('DOCKER_HOST')
        !observed().containsKey('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')
        observed().containsKey('DOCKER_CONFIG')

        where:
        machine                        | preset
        'no docker binary'             | null
        'a context that fails inspect' | 'docker-daemon-down'
    }

    private BuildResult run(String os, Path docker, Map<String, String> operator, String... tasks) {
        runner(os, docker, operator, tasks).build()
    }

    /** The inner build, launched with the operator's Docker variables replaced by {@code operator}. */
    private GradleRunner runner(String os, Path docker, Map<String, String> operator, String... tasks) {
        Map<String, String> environment = new HashMap<>(System.getenv())
        environment.keySet().removeAll(['DOCKER_HOST', 'DOCKER_CONFIG', 'DOCKER_CONTEXT', 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'])
        environment.putAll(operator)
        String[] names = tasks.length == 0 ? ['probe'] as String[] : tasks
        GradleRunnerSupport.runner(projectDir, *names, "-Pos=${os}", "-Pdocker=${docker}")
                .withEnvironment(environment)
    }

    /** The environment the {@code env} of {@code task} printed. */
    private Map<String, String> observed(String task = 'probe') {
        projectDir.resolve("${task}.env").readLines().findAll { it.contains('=') }.collectEntries {
            [(it.substring(0, it.indexOf('='))): it.substring(it.indexOf('=') + 1)]
        }
    }

    /** A per-run link to the context preset, named after the answer of {@code data/stdout-docker-context} it gives. */
    private Path contextAnswering(String answer) {
        Path dir = Files.createDirectories(home.resolve('run'))
        Files.createSymbolicLink(dir.resolve(answer), presetLink('docker-context-inspect'))
    }

    /** The preset's own link onto the committed script, as {@code StandIn} in {@code :test-fixtures} makes it. */
    private Path presetLink(String preset) {
        Path link = home.resolve(preset)
        Files.isSymbolicLink(link) ? link : Files.createSymbolicLink(link,
                Path.of(GradleRunnerSupport.requiredProperty('gnomish.standInLibrary')).resolve('stand-in.sh'))
    }

    private static Path log(Path link) {
        link.resolveSibling("${link.fileName}.log")
    }

    /** The blocks the per-run link's {@code record} row appended, one per run. */
    private static List<String> records(Path link) {
        Files.exists(log(link)) ? log(link).text.split(/(?m)^--$/).findAll { !it.isBlank() }.toList() : []
    }
}
