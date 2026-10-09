package com.github.oinsio.gnomish.build

import static org.gradle.testkit.runner.TaskOutcome.SKIPPED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Path
import java.util.regex.Pattern
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code pitest-cost-conventions} (FR3, FR4, NFR-C1, NG4, UX1 of
 * kill-expensive-mutants; design D5): {@code pitestCostReport} run directly over a canned {@code
 * mutations.xml}, so no PIT run is needed. The module applies {@code pitest-gate-conventions}, which
 * applies the report, and runs in the miniature git repository of {@code MutationScopeConventionsFunctionalSpec}
 * — {@code -PpitScope=all} puts it in scope, a clean branch without it skips it.
 */
class PitestCostReportFunctionalSpec extends Specification {

    static final String REPORT = 'mod-a/build/reports/pitest/mutations.xml'
    static final String TASK = ':mod-a:pitestCostReport'
    static final String ALL = '-PpitScope=all'

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniScopeRepository repo

    def setup() {
        repo = new MiniScopeRepository(projectDir, home)
        repo.write('settings.gradle', "rootProject.name = 'mini-cost'\ninclude 'mod-a'\n")
        repo.write('build.gradle', "tasks.register('pitestAll')\n")
        repo.write('mod-a/build.gradle', "plugins {\n    id 'java'\n    id 'pitest-gate-conventions'\n}\n")
        repo.write('mod-a/src/main/java/mini/Foo.java', 'package mini;\n\npublic final class Foo {}\n')
        repo.init([:]).write('README', 'mini\n').commit('second commit on main')
        repo.git('checkout', '-q', '-b', 'feat')
    }

    def "FR3: the file lists only the mutants above the threshold, most expensive first, one tab-separated row each"() {
        given: 'three mutants above 100 test executions, two at or below it'
        cannedReport([mutant('mini.A', 'alpha', 10, 'MathMutator', 150, 'mini.ASpec.alpha'),
                      mutant('mini.B', 'beta', 20, 'NegateConditionalsMutator', 40, 'mini.BSpec.beta'),
                      mutant('mini.C', 'gamma', 30, 'VoidMethodCallMutator', 675, 'mini.CSpec.gamma'),
                      mutant('mini.D', 'delta', 40, 'MathMutator', 100, 'mini.DSpec.delta'),
                      mutant('mini.E', 'epsilon', 50, 'ConditionalsBoundaryMutator', 101, '')])

        when:
        BuildResult result = repo.runner(TASK, ALL).build()

        then:
        result.task(TASK).outcome == SUCCESS
        def lines = costFile().readLines()
        lines[0].startsWith('# PIT mutation cost of :mod-a')
        lines[1] == ['class', 'method', 'line', 'mutator', 'count', 'killing test'].join('\t')
        lines.drop(2) == [['mini.C', 'gamma', '30', 'VoidMethodCallMutator', '675', 'mini.CSpec.gamma'],
                          ['mini.A', 'alpha', '10', 'MathMutator', '150', 'mini.ASpec.alpha'],
                          ['mini.E', 'epsilon', '50', 'ConditionalsBoundaryMutator', '101', '-']]*.join('\t')
    }

    def "UX1, FR4: one lifecycle line names the most expensive mutant as class.method:line (mutator) — N tests"() {
        given:
        cannedReport([mutant('mini.A', 'alpha', 10, 'MathMutator', 150, 'mini.ASpec.alpha'),
                      mutant('mini.C', 'gamma', 30, 'VoidMethodCallMutator', 675, 'mini.CSpec.gamma')])

        when:
        BuildResult result = repo.runner(TASK, ALL).build()

        then:
        def costLines = result.output.readLines().findAll { it.startsWith('PIT mutation cost') }
        costLines.size() == 1
        costLines[0].startsWith('PIT mutation cost :mod-a: 2 above threshold (100 tests)')
        costLines[0].contains('most expensive mini.C.gamma:30 (VoidMethodCallMutator) — 675 tests')
        costLines[0].contains('a fast killing spec answers it')
        costLines[0].endsWith(costFile().toString())
    }

    def "FR4: no mutations.xml means no report — the task is skipped, not failed"() {
        when:
        BuildResult result = repo.runner(TASK, ALL).build()

        then:
        result.task(TASK).outcome == SKIPPED
        !costFile().toFile().exists()
        !result.output.contains('PIT mutation cost')
    }

    def "FR4, D5: a module the scope skips is skipped as a whole — a stale mutations.xml is not described as this build's"() {
        given: 'an earlier run left an expensive report behind, and the branch changes nothing'
        cannedReport([mutant('mini.C', 'gamma', 30, 'VoidMethodCallMutator', 675, 'mini.CSpec.gamma')])

        when:
        BuildResult result = repo.runner(TASK).build()

        then:
        result.task(TASK).outcome == SKIPPED
        !costFile().toFile().exists()
        !result.output.contains('PIT mutation cost')
    }

    def "NG4, NFR-C1: a 10 000-count mutant does not fail the build, and the report task takes well under 5 s"() {
        given:
        cannedReport([mutant('mini.Z', 'zeta', 7, 'MathMutator', 10_000, 'mini.ZSpec.zeta')])

        when:
        BuildResult result = repo.runner(TASK, ALL, '--profile').build()

        then:
        result.task(TASK).outcome == SUCCESS
        costFile().readLines().last().contains('\t10000\t')
        profiledSeconds(TASK) < 5
    }

    private void cannedReport(List<String> mutants) {
        repo.write(REPORT, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"false\">\n${mutants.join('\n')}\n</mutations>\n")
    }

    private Path costFile() {
        projectDir.resolve('mod-a/build/reports/pitest/expensive-mutants.txt')
    }

    /**
     * The task's own duration from the "Task Execution" table of the HTML report {@code --profile}
     * writes under the root build directory — a row {@code <td class="indentPath">:path</td>
     * <td class="numeric">0.030s</td>}, the duration in Gradle's {@code 1m2.345s} form.
     */
    private double profiledSeconds(String task) {
        def profile = projectDir.resolve('build/reports/profile').toFile().listFiles().find { it.name.endsWith('.html') }
        assert profile != null: 'the build wrote no --profile report'
        def row = profile.text =~ /(?s)<td[^>]*>${Pattern.quote(task)}<\/td>\s*<td class="numeric">([^<]*)<\/td>/
        assert row.find(): "no ${task} row in ${profile}"
        def duration = row.group(1)
        def minutes = duration =~ /(\d+)m/
        def seconds = duration =~ /([\d.]+)s/
        (minutes.find() ? (minutes.group(1) as double) * 60 : 0) + (seconds.find() ? seconds.group(1) as double : 0)
    }

    private static String mutant(String cls, String method, int line, String mutator, int count, String killingTest) {
        """<mutation detected="true" status="KILLED" numberOfTestsRun="${count}"><sourceFile>X.java</sourceFile>\
<mutatedClass>${cls}</mutatedClass><mutatedMethod>${method}</mutatedMethod><methodDescription>()V</methodDescription>\
<lineNumber>${line}</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.${mutator}</mutator>\
<indexes><index>1</index></indexes><blocks><block>0</block></blocks><killingTest>${killingTest}</killingTest>\
<description>d</description></mutation>"""
    }
}
