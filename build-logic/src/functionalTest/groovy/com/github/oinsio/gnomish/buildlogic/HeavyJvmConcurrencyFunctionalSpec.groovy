package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.BuildResult

/**
 * What the heavy-JVM budget does to a parallel build (FR2, FR4, G2 of adapt-build-load-to-hardware):
 * three modules' {@code test} tasks run with {@code --parallel}, and the intervals they held their
 * slot in are compared — at most the budget overlap — while a light task keeps running beside a
 * saturated budget. The machine is the one the build sees: this host, or one with its processor
 * count capped ({@link HeavyJvmBudgetSpecBase}).
 */
class HeavyJvmConcurrencyFunctionalSpec extends HeavyJvmBudgetSpecBase {

    static final List<String> HEAVY = ['mod-a', 'mod-b', 'mod-c']

    // Scenario: build-system/quality-gates / Override takes precedence
    def "FR4: with the override set to #slots, at most #slots of three heavy tasks hold a slot at once"() {
        given:
        HEAVY.each { heavyModule(it) }
        include(HEAVY)

        when:
        BuildResult result = run('test', "-P${OVERRIDE}=${slots}")

        then: 'every heavy task ran, and never more of them at once than the override allows'
        HEAVY.every { result.task(":${it}:test").outcome == SUCCESS }
        recordedIntervals() == 3
        maxOverlap() <= slots

        and: 'the bound is the override\'s, not this machine\'s, whose formula allows more than one'
        slots > 1 || budgetLines(result)[0].contains('source: gnomish.heavyJvmSlots=1 (override)')

        where:
        slots << [1, 2]
    }

    // Scenario: build-system/quality-gates / Concurrency scales with the machine
    def "FR1, FR2: on a four-core machine the formula allows two heavy JVMs, and three heavy tasks never run three at once"() {
        given: 'four cores: min(cores - 2, RAM slots) is two on any machine with at least 14 GB'
        machineWithCores(4)
        HEAVY.each { heavyModule(it, 3000) }
        include(HEAVY)

        when:
        BuildResult result = run('test')

        then: 'the budget was derived from the machine, and the build completed green'
        budgetLines(result).size() == 1
        budgetLines(result)[0].startsWith('Heavy-JVM budget: 2 concurrent test/mutation JVM(s)')
        budgetLines(result)[0].contains(', 4 cores; source: hardware formula')
        HEAVY.every { result.task(":${it}:test").outcome == SUCCESS }

        and: 'two ran together, never three'
        maxOverlap() == 2
    }

    // Scenario: build-system/quality-gates / Light tasks keep full parallelism
    def "G2: a compilation runs while the only heavy slot is held — each waits to see the other start"() {
        given: 'one slot, held by mod-a\'s test until mod-b\'s compileJava has started, and the reverse'
        def marks = projectDir.resolve('build/marks').toString()
        heavyModule('mod-a', 0, handshake(marks, 'heavy', 'light'))
        repo.write('mod-b/build.gradle', """\
plugins {
    id 'library-conventions'
}
tasks.named('compileJava') {
    doFirst('meet the heavy task') {
        ${handshake(marks, 'light', 'heavy')}
    }
}
""")
        repo.write('mod-b/src/main/java/com/github/oinsio/gnomish/mini/Light.java',
                'package com.github.oinsio.gnomish.mini;\n\npublic final class Light {\n    private Light() {}\n}\n')
        include(['mod-a', 'mod-b'])

        when: 'a compilation that had to wait for the heavy slot would time out, failing the build'
        BuildResult result = run(':mod-a:test', ':mod-b:compileJava', "-P${OVERRIDE}=1")

        then:
        result.task(':mod-a:test').outcome == SUCCESS
        result.task(':mod-b:compileJava').outcome == SUCCESS
    }

    /** Marks {@code self} as started, then waits up to a minute for {@code other} to have started. */
    private static String handshake(String marks, String self, String other) {
        """\
def marks = new File('${GradleRunnerSupport.quotedPath(marks)}')
        marks.mkdirs()
        new File(marks, '${self}').text = 'started'
        long deadline = System.currentTimeMillis() + 60_000
        while (!new File(marks, '${other}').exists()) {
            if (System.currentTimeMillis() > deadline) {
                throw new GradleException('${self} task never ran beside the ${other} task')
            }
            Thread.sleep(50)
        }"""
    }
}
