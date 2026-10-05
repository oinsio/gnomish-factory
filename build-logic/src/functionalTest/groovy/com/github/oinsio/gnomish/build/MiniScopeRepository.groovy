package com.github.oinsio.gnomish.build

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

/**
 * A miniature git repository holding a mini build that obtains {@code MutationScopeSource} and
 * prints the scope it got — the fixture of {@code MutationScopeFunctionalSpec}. {@code
 * MutationScopeConventionsFunctionalSpec} overwrites that build with one applying the real
 * convention plugins and drives it through {@link #runner}, keeping the repository and its git
 * configuration.
 *
 * <p>The repository owns its git configuration (Risks of scope-pit-locally): {@code build-logic}
 * does not apply {@code adversarial-gitconfig-conventions}, so without this the repository would
 * commit under the developer's {@code ~/.gitconfig}, and with no identity at all on a CI runner.
 * Both the fixture's own {@code git} and the build under test get {@code GIT_CONFIG_GLOBAL} pointing
 * at a file this class writes, and {@code GIT_CEILING_DIRECTORIES} so a temporary directory that
 * happens to sit in a checkout is never mistaken for one.
 *
 * <p>The build under test runs {@code git} through a wrapper, handed to the source as its
 * {@code gitExecutable}, that appends each invocation — {@code GIT_OPTIONAL_LOCKS} value, then argv —
 * to {@code #invocations()}, so a scenario can count the processes (NFR-P1) and see their
 * environment (NFR-R4) without tracing. A {@code PATH} entry cannot do this: the JDK resolves the
 * executable against the daemon's own {@code PATH}, not the environment TestKit hands the build.
 */
final class MiniScopeRepository {

    /** {@code :mod-a:inner} nests inside {@code :mod-a}, as {@code sandbox/core} does in the real build. */
    static final Map<String, String> MODULES = [':mod-a': 'mod-a', ':mod-a:inner': 'mod-a/inner', ':mod-b': 'mod-b',
                                                ':test-fixtures': 'test-fixtures']

    final Path dir
    final Path globalConfig
    private final Path invocationLog
    private final Path wrapper

    MiniScopeRepository(Path dir, Path home) {
        this.dir = dir
        this.globalConfig = home.resolve('gitconfig')
        this.invocationLog = home.resolve('git-invocations.log')
        this.wrapper = home.resolve('git-recorder')
        globalConfig.text = '[user]\n\tname = Mini Scope\n\temail = mini@example.invalid\n'
        wrapper.text = """#!/bin/sh
printf '%s %s\\n' "\${GIT_OPTIONAL_LOCKS:-unset}" "\$*" >> '${invocationLog}'
exec '${realGit()}' "\$@"
"""
        wrapper.toFile().setExecutable(true)
        writeBuild()
    }

    /**
     * {@code git init} on {@code main} with the mini build ignored and {@code files} committed; given a
     * {@code mainEdit}, a second commit then edits {@code mainEdit}, so {@code main}'s last commit
     * has changes of its own that a fresh branch must not inherit.
     */
    MiniScopeRepository init(Map<String, String> files, String mainEdit = null) {
        git('init', '-q', '-b', 'main')
        write('.gitignore', '.gradle/\nbuild/\n')
        files.each { path, content -> write(path, content) }
        commit('initial')
        mainEdit == null ? this : write(mainEdit, '// edited on main\n').commit('last commit on main')
    }

    MiniScopeRepository write(String path, String content = "// ${path}\n") {
        GradleRunnerSupport.writeFile(dir, path, content)
        this
    }

    MiniScopeRepository commit(String message) {
        git('add', '-A')
        git('commit', '-q', '-m', message)
        this
    }

    String git(String... args) {
        def process = new ProcessBuilder(['git', *args]).directory(dir.toFile()).redirectErrorStream(true)
        process.environment().putAll(gitEnvironment())
        def started = process.start()
        def output = started.inputStream.getText('UTF-8')
        assert started.waitFor() == 0: "git ${args.join(' ')} failed:\n${output}"
        output.trim()
    }

    /** The {@code git} lines the build under test has run. */
    List<String> invocations() {
        Files.exists(invocationLog) ? invocationLog.readLines() : []
    }

    /** Runs the mini build and returns the scope it printed, plus the raw result. */
    Map scope(String... arguments) {
        BuildResult result = runner(arguments).build()
        def line = result.output.readLines().find { it.startsWith('SCOPE ') }
        assert line != null: 'the mini build printed no scope'
        (new JsonSlurper().parseText(line.substring('SCOPE '.length())) as Map) + [output: result.output]
    }

    /** Runs whatever build the repository holds — a caller may replace the scope-printing one. */
    GradleRunner runner(String... arguments) {
        def environment = new HashMap<String, String>(System.getenv())
        environment.keySet().removeIf { it.startsWith('GIT_') }
        environment.putAll(gitEnvironment())
        String[] tasks = arguments
        if (tasks.length == 0) {
            tasks = new String[] {'printScope'}
        }
        GradleRunnerSupport.runner(dir, tasks)
                .withEnvironment(environment)
    }

    private Map<String, String> gitEnvironment() {
        [GIT_CONFIG_GLOBAL      : globalConfig.toString(),
         GIT_CONFIG_NOSYSTEM    : '1',
         GIT_CEILING_DIRECTORIES: dir.parent.toString()]
    }

    private void writeBuild() {
        write('settings.gradle', "rootProject.name = 'mini-scope'\n")
        write('build.gradle', """\
// No convention plugin is applied (nothing to resolve, so every run stays offline): the scope
// owner's classes go on the build script's own classpath straight off TestKit's plugin classpath.
buildscript {
    dependencies {
        classpath files(${pluginClasspath().collect { "'" + GradleRunnerSupport.quotedPath(it) + "'" }.join(', ')})
    }
}

import com.github.oinsio.gnomish.build.MutationScopeSource
import groovy.json.JsonOutput

// Obtained at configuration time, as the module scripts will: the configuration cache then
// fingerprints the value and re-validates it on every build (NFR-R2).
def obtained = providers.of(MutationScopeSource) {
    parameters.repositoryRoot = layout.projectDirectory
    parameters.moduleDirs = [${MODULES.collect { k, v -> "'${k}': '${v}'" }.join(', ')}]
    parameters.requested = providers.gradleProperty('pitScope')
    parameters.taskNames = gradle.startParameter.taskNames
    parameters.gitExecutable = '${GradleRunnerSupport.quotedPath(wrapper.toString())}'
}.get()
def printed = JsonOutput.toJson([mode: obtained.mode.name(), base: obtained.base, baseOrigin: obtained.baseOrigin,
        reason: obtained.reason, globs: obtained.globs, changedClasses: obtained.changedClasses,
        widened: obtained.widened])

tasks.register('printScope') {
    doLast { println "SCOPE \${printed}" }
}
tasks.register('pitestAll') {
    dependsOn 'printScope'
}
""")
    }

    /** The build-logic classes TestKit publishes for the build under test. */
    private static List<String> pluginClasspath() {
        Properties metadata = new Properties()
        MiniScopeRepository.classLoader
                .getResourceAsStream('plugin-under-test-metadata.properties')
                .withCloseable { metadata.load(it) }
        metadata.getProperty('implementation-classpath').split(File.pathSeparator).toList()
    }

    /** The {@code git} the wrapper delegates to — resolved before the wrapper is on any PATH. */
    private static String realGit() {
        def lookup = new ProcessBuilder('sh', '-c', 'command -v git').start()
        def path = lookup.inputStream.text.trim()
        assert lookup.waitFor() == 0 && !path.isEmpty(): 'functionalTest needs git on PATH'
        path
    }
}
