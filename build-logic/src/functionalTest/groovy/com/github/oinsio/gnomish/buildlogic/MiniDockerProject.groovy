package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

/**
 * A miniature build that hands {@code TestDockerEnvironment} the host it composes for — the
 * fixture of {@link TestDockerEnvironmentFunctionalSpec}. The operating system and the
 * {@code docker} to run are build properties, never this machine's; the forked process is
 * {@code env} under two {@code Exec} tasks, {@code probe} and {@code probeAgain}, each of which
 * leaves the environment it observed in {@code <task>.env}.
 *
 * <p>The {@code docker} is the committed stand-in preset {@code docker-context-inspect}, reached
 * through a per-run link named after the endpoint it answers, or after the binary itself when a
 * {@code PATH} entry must find it by its bare name; each run is recorded beside the link.
 */
final class MiniDockerProject {

    private final Path projectDir
    private final Path home

    MiniDockerProject(Path projectDir, Path home) {
        this.projectDir = projectDir
        this.home = home
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

    BuildResult run(String os, Object docker, Map<String, String> operator, String... tasks) {
        runner(os, docker, operator, tasks).build()
    }

    /**
     * The inner build, launched with the operator's Docker variables replaced by {@code operator},
     * which may also carry the client's {@code PATH}; {@code docker} is a path or a bare name.
     */
    GradleRunner runner(String os, Object docker, Map<String, String> operator, String... tasks) {
        Map<String, String> environment = new HashMap<>(System.getenv())
        environment.keySet().removeAll(['DOCKER_HOST', 'DOCKER_CONFIG', 'DOCKER_CONTEXT', 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'])
        environment.putAll(operator)
        String[] names = tasks.length == 0 ? (['probe'] as String[]) : tasks
        GradleRunnerSupport.runner(projectDir, *names, "-Pos=${os}", "-Pdocker=${docker}")
                .withEnvironment(environment)
    }

    /** The environment the {@code env} of {@code task} printed. */
    Map<String, String> observed(String task = 'probe') {
        projectDir.resolve("${task}.env").readLines().findAll { it.contains('=') }.collectEntries {
            [(it.substring(0, it.indexOf('='))): it.substring(it.indexOf('=') + 1)]
        }
    }

    /** A per-run link to the context preset in {@code dir}, named after the answer of {@code data/stdout-docker-context} it gives. */
    Path contextAnswering(String answer, String dir = 'run') {
        Path parent = Files.createDirectories(home.resolve(dir))
        Files.createSymbolicLink(parent.resolve(answer), presetLink('docker-context-inspect'))
    }

    /** The preset's own link onto the committed script, as {@code StandIn} in {@code :test-fixtures} makes it. */
    Path presetLink(String preset) {
        Path link = home.resolve(preset)
        Files.isSymbolicLink(link) ? link : Files.createSymbolicLink(link,
                Path.of(GradleRunnerSupport.requiredProperty('gnomish.standInLibrary')).resolve('stand-in.sh'))
    }

    static Path log(Path link) {
        link.resolveSibling("${link.fileName}.log")
    }

    /** The blocks the per-run link's {@code record} row appended, one per run. */
    static List<String> records(Path link) {
        Files.exists(log(link)) ? log(link).text.split(/(?m)^--$/).findAll { !it.isBlank() }.toList() : []
    }
}
