package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The miniature multi-module build of the heavy-JVM budget specs (adapt-build-load-to-hardware):
 * modules applying the real {@code library-conventions}, hence the real {@code test-conventions}
 * that registers {@code HeavyJvmBudget}, in the git repository of {@link MiniScopeRepository}.
 *
 * <p>A heavy module's {@code test} task is given a placeholder test-classes directory, so it runs
 * (and takes its slot) without compiling a spec, and an action that holds the slot for a moment and
 * records the interval it held it in. The budget is then read from what the build did — how many
 * of those intervals overlapped — and from the one line the service logs, never from the
 * registration. A machine is made smaller than this one by capping the daemon's processor count
 * ({@code -XX:ActiveProcessorCount}), which is what {@code HardwareSpec} reads.
 */
abstract class HeavyJvmBudgetSpecBase extends Specification {

    static final String OVERRIDE = 'gnomish.heavyJvmSlots'

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniScopeRepository repo

    def setup() {
        repo = new MiniScopeRepository(projectDir, home)
        repo.write('build.gradle', "tasks.register('pitestAll')\n")
        repo.writeTestConventionInputs()
    }

    /** Writes the settings including {@code modules}, and commits the tree. */
    void include(List<String> modules) {
        repo.write('settings.gradle', """\
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog'))}'))
        }
    }
}
rootProject.name = 'mini-heavy-jvm'
includeBuild '${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.buildChecksDir'))}'
include ${modules.collect { "'${it}'" }.join(', ')}
""")
        repo.init([:])
    }

    /** A module whose {@code test} holds its slot for {@code holdMillis}, then records the interval. */
    void heavyModule(String name, int holdMillis = 1500, String extra = '') {
        repo.write("${name}/placeholder-test-classes/none.txt", 'no test classes\n')
        repo.write("${name}/build.gradle", """\
plugins {
    id 'library-conventions'
}
def record = rootProject.layout.projectDirectory.file('build/intervals/${name}.txt').asFile
tasks.named('test') {
    testClassesDirs = files('placeholder-test-classes')
    failOnNoDiscoveredTests = false
    doFirst('hold the heavy-JVM slot') {
        long start = System.nanoTime()
        ${extra}
        Thread.sleep(${holdMillis})
        record.parentFile.mkdirs()
        record.text = "\${start} \${System.nanoTime()}"
    }
}
""")
    }

    /** Pins the daemon's processor count, as a machine with {@code cores} cores would report it. */
    void machineWithCores(int cores) {
        repo.write('gradle.properties', "org.gradle.jvmargs=-Xmx1g -XX:ActiveProcessorCount=${cores}\n")
    }

    BuildResult run(String... arguments) {
        repo.runner([*arguments, '--parallel'] as String[]).build()
    }

    /** The service's decision lines; the budget is announced once per build. */
    static List<String> budgetLines(BuildResult result) {
        result.output.readLines().findAll { it.startsWith('Heavy-JVM budget:') }
    }

    /** The most heavy tasks that held a slot at one instant, from the recorded intervals. */
    int maxOverlap() {
        def dir = projectDir.resolve('build/intervals')
        def intervals = Files.list(dir).withCloseable { files ->
            files.toList().collect { it.text.tokenize(' ').collect { it as long } }
        }
        intervals.collect { probe -> intervals.count { it[0] <= probe[0] && probe[0] < it[1] } }.max() as int
    }

    int recordedIntervals() {
        def dir = projectDir.resolve('build/intervals')
        Files.isDirectory(dir) ? Files.list(dir).withCloseable { it.count() } as int : 0
    }
}
