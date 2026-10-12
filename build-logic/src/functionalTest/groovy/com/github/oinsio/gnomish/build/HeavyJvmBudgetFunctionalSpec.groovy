package com.github.oinsio.gnomish.build

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult

/**
 * How the heavy-JVM budget is decided and announced (FR1, FR3, FR4, NFR-O1, NFR-R1, UX2 of
 * adapt-build-load-to-hardware): the one line {@code HeavyJvmBudget} logs when the first heavy task
 * starts names the slot count, the detected RAM and cores, and where the count came from; the
 * formula floors at one slot; and nothing committed tunes the worker count.
 */
class HeavyJvmBudgetFunctionalSpec extends HeavyJvmBudgetSpecBase {

    static final String WORKER_CAP = 'org.gradle.' + 'workers.max'

    // Scenario: build/quality-gates / Computed budget is visible
    def "NFR-O1: without the override the line names the computed budget, the detected RAM and the core count"() {
        given:
        heavyModule('mod-a', 0)
        include(['mod-a'])
        int ramGb = (int) (((OperatingSystemMXBean) ManagementFactory.operatingSystemMXBean).totalMemorySize / (1L << 30))
        int cores = Runtime.runtime.availableProcessors()
        int expected = Math.max(1, Math.min(cores - 2, (ramGb - 8).intdiv(3) as int))

        when:
        BuildResult result = run('test')

        then:
        budgetLines(result) == ["Heavy-JVM budget: ${expected} concurrent test/mutation JVM(s) — detected ${ramGb} GB RAM, " +
                "${cores} cores; source: hardware formula min(cores-2, (ram-4-4)/3)".toString()]
    }

    // Scenario: build/quality-gates / Override is discoverable
    def "UX2: with the override set the line names the property and its value as the budget's source"() {
        given:
        heavyModule('mod-a', 0)
        include(['mod-a'])

        when:
        BuildResult result = run('test', "-P${OVERRIDE}=3")

        then:
        budgetLines(result).size() == 1
        budgetLines(result)[0].startsWith('Heavy-JVM budget: 3 concurrent test/mutation JVM(s)')
        budgetLines(result)[0].endsWith('source: gnomish.heavyJvmSlots=3 (override)')
    }

    // Scenario: build/quality-gates / Small machine degrades to serial heavy tasks
    def "NFR-R1: a single-core machine, where the formula yields a negative count, gets one slot and still builds"() {
        given: 'one core: cores - 2 is -1'
        machineWithCores(1)
        ['mod-a', 'mod-b'].each { heavyModule(it, 500) }
        include(['mod-a', 'mod-b'])

        when:
        BuildResult result = run('test')

        then: 'the build configured and executed, on one heavy slot'
        budgetLines(result).size() == 1
        budgetLines(result)[0].startsWith('Heavy-JVM budget: 1 concurrent test/mutation JVM(s)')
        budgetLines(result)[0].contains(', 1 cores; source: hardware formula')
        result.task(':mod-a:test').outcome == SUCCESS
        result.task(':mod-b:test').outcome == SUCCESS
        maxOverlap() == 1
    }

    // Scenario: build/quality-gates / Fresh clone needs no tuning
    // F118: text aligned to code (8.15)
    def "FR3: no committed properties file caps the workers, and an untuned build of the conventions passes"() {
        given: 'every properties file of the repository'
        def repoRoot = Path.of(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog')).parent.parent
        def properties = Files.walk(repoRoot).withCloseable { paths ->
            paths.filter { it.fileName.toString() == 'gradle.properties' }
                    .filter { !repoRoot.relativize(it).any { it.toString() in ['build', '.gradle', 'node_modules'] } }
                    .toList()
        }

        expect: 'the scan reached the root file, and no file sets the cap'
        properties.contains(repoRoot.resolve('gradle.properties'))
        properties.findAll { file -> file.readLines().any { it.trim().startsWith(WORKER_CAP) } } == []

        when: 'a build with no tuning of its own runs the conventions'
        heavyModule('mod-a', 0)
        include(['mod-a'])
        BuildResult result = run('test')

        then:
        result.task(':mod-a:test').outcome == SUCCESS
    }
}
