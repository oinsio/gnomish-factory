package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code LogExpectationGateCheck}, the verdict half of the runtime
 * log-expectation gate (FR17, design D16 of harden-logging-observability): the task is RUN over
 * observation files in the exact row shape the Spock extension's ledger writes ({@code watched},
 * {@code unwatched}, {@code allowed}, tab-separated), and its outcome is the build's.
 *
 * <p>Hermetic: the mini project puts the task type on its build script's classpath straight off
 * TestKit's plugin classpath, so nothing is resolved and every run is {@code --offline}.
 */
class LogExpectationGateCheckFunctionalSpec extends Specification {

    private static final String CODE = 'GF101'

    @TempDir
    Path projectDir

    def setup() {
        GradleRunnerSupport.writeFile(projectDir, 'settings.gradle', "rootProject.name = 'mini-log-gate'\n")
        GradleRunnerSupport.writeFile(projectDir, 'build.gradle', """\
buildscript {
    dependencies {
        classpath files(${GradleRunnerSupport.pluginClasspath().collect { "'" + GradleRunnerSupport.quotedPath(it) + "'" }.join(', ')})
    }
}

import com.github.oinsio.gnomish.buildlogic.LogExpectationGateCheck

tasks.register('checkLogExpectationGate', LogExpectationGateCheck) {
    observations.from(fileTree('observations') { include 'observations-*.tsv' })
    report = layout.buildDirectory.file('reports/log-expectation-gate.txt')
}
""")
    }

    /** One test JVM's evidence file, rows joined as the ledger joins them. */
    private void observed(String jvm, List<String> rows) {
        GradleRunnerSupport.writeFile(projectDir, "observations/observations-${jvm}.tsv", rows.join('\n') + '\n')
    }

    private static String row(String kind, String feature) {
        [kind, CODE, feature, 'WARN', 'com.example.Emitter', "[$CODE] a degrade line"].join('\t')
    }

    private BuildResult run() {
        GradleRunnerSupport.runner(projectDir, 'checkLogExpectationGate').build()
    }

    private BuildResult runAndFail() {
        GradleRunnerSupport.runner(projectDir, 'checkLogExpectationGate').buildAndFail()
    }

    // The static half is LogContractGateSpec's "every code in use is named by at least one test
    //     source"; this is the runtime half, the build that emitted the line with nothing watching.
    // Scenario: observability/factory-logging / A degrade line cannot land unasserted
    def "FR17: a code emitted with no capture watching it anywhere in the run fails the build, naming the code"() {
        given:
        observed('1', [row('unwatched', 'EmitterSpec.a degrade path')])

        when:
        def result = runAndFail()

        then:
        result.output.contains("operator log event code(s) were emitted")
        result.output.contains(CODE)
    }

    // Scenario: observability/factory-logging / Crossing a pinned degrade path is reported, not failed
    def "FR17: a code one spec pins and another merely crosses is counted in the report and does not fail the build"() {
        given: 'one JVM\'s spec pinned the code; another JVM\'s behavior spec traversed it unwatched'
        observed('1', ["watched\t$CODE"])
        observed('2', [row('unwatched', 'BehaviorSpec.crosses the degrade path')])

        when:
        def result = run()

        then: 'the traversal is in the report as an emission nothing in its feature watched, and nothing fails'
        def report = projectDir.resolve('build/reports/log-expectation-gate.txt').text
        report.contains('Codes emitted with nothing watching: 1')
        report.contains('Failing (emitted, never watched anywhere in this run): 0')
        result.output.contains('BUILD SUCCESSFUL')
    }

    // Scenario: observability/factory-logging / A declared allowance excuses an unwatched code
    def "FR17: an allowance on the emitting feature excuses the code, and the same code without one fails"() {
        given: 'the emitting feature carried @AllowsUnexpectedLogEvents, so its ledger row is an allowed one'
        observed('1', [row('allowed', 'EmitterSpec.a deliberate traversal')])

        expect: 'nothing watched it, and the build still passes'
        run().output.contains('BUILD SUCCESSFUL')

        when: 'the same emission with no allowance and no watching capture'
        observed('1', [row('unwatched', 'EmitterSpec.a deliberate traversal')])

        then:
        runAndFail().output.contains(CODE)
    }
}
