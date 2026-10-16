package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SKIPPED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import org.gradle.testkit.runner.BuildResult

/**
 * Behavioral verification of the scope's CONSUMER — {@code pitest-scope-conventions} with
 * {@code pitest-gate-conventions} (D1, D6, NFR-R3, NFR-O1, M3 of scope-pit-locally). {@code
 * MutationScopeFunctionalSpec} pins what {@code MutationScopeSource} returns; this suite pins what
 * the build does with it: the targets PIT really mutates, the one skip the verdict shares, and the
 * one {@code PIT scope:} line per build.
 *
 * <p>The miniature repository holds one module applying the real {@code library-conventions}
 * (hence the real {@code pitest-conventions}) over the repository's own version catalog and the
 * real {@code build-checks}, and every mutation run is a real PIT run, offline against the outer
 * build's Gradle user home. {@code Foo} has a killing spec; {@code Bar} is in {@code
 * excludedClasses}.
 */
class MutationScopeConventionsFunctionalSpec extends MutationScopeConventionsSpecBase {

    // Scenario: build-system/quality-gates / Diff with no production changes still passes
    // Scenario: build-system/quality-gates / Empty scope is a clean pass
    // Scenario: build-system/quality-gates / A run that mutates nothing says so
    def "NFR-R3, NFR-O1: a clean branch skips the module as a whole, a stale report is not judged, the line still prints"() {
        given: 'an earlier run left a report with a survivor'
        repo.write(STALE_REPORT, '<mutations><mutation status="SURVIVED"/></mutations>')

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SKIPPED
        result.task(GATE).outcome == SKIPPED
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — every module skipped (1)"]
    }

    def "NFR-O1, NFR-R2: a configuration-cache hit still announces the scope exactly once"() {
        when:
        run(GATE, '--configuration-cache')
        BuildResult second = run(GATE, '--configuration-cache')

        then:
        second.output.contains('Reusing configuration cache.')
        scopeLines(second).size() == 1
    }

    // Scenario: build-system/quality-gates / Diff with no production changes still passes
    // Scenario: build-system/quality-gates / Empty scope is a clean pass
    def "D1: a change to only an excluded class leaves nothing to mutate, so PIT is skipped, not failed"() {
        given:
        repo.write(BAR, javaClass('Bar', 'public static int one() { return 2 - 1; }'))

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SKIPPED
        !result.output.contains('No mutations found')
    }

    // Scenario: build-system/quality-gates / Default run mutates the branch's changed classes
    // Scenario: build-system/quality-gates / A branch run explains itself
    def "D1, NFR-O1: a changed class is mutated and the line counts it"() {
        given:
        repo.write(FOO, javaClass('Foo', 'public static int twice(int x) { return x + x; }'))

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SUCCESS
        result.task(GATE).outcome == SUCCESS
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — mod-a: 1 class — 0 modules skipped"]
    }

    // Scenario: build-system/quality-gates / Surviving mutant fails the build
    // Scenario: build-system/quality-gates / A weakened spec is caught
    // Scenario: build-system/quality-gates / A branch run explains itself
    def "M3, FR3: deleting an assertion from a killing spec widens the module, and the gate fails on the survivor"() {
        given: 'the only production-relevant edit is the weakened spec'
        repo.write(SPEC, spec('when: Foo.twice(3)\n        then: noExceptionThrown()'))

        when:
        BuildResult result = repo.runner(GATE).buildAndFail()

        then:
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — mod-a: whole module (test change) — 0 modules skipped"]
        result.task(':mod-a:pitest').outcome == FAILED
        result.output.contains('below threshold')
    }

    // Scenario: build-system/quality-gates / No resolvable base preserves full-project mutation
    // Scenario: build-system/quality-gates / No resolvable base widens to the whole tree
    // Scenario: build-system/quality-gates / A fallback is visible
    def "NFR-R1, NFR-O1: with no resolvable base the whole tree is mutated and the line says why"() {
        given:
        repo.git('branch', '-q', '-m', 'main', 'trunk')

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SUCCESS
        scopeLines(result).size() == 1
        scopeLines(result)[0].startsWith('PIT scope: all — whole tree (no scope base:')
        scopeLines(result)[0].endsWith('— 1 modules mutated — 0 modules skipped')
    }

    // Scenario: build-system/quality-gates / An abbreviated pitestAll is refused
    def "D5: pitestAll requested by an abbreviation fails before any module runs, rather than mutating the branch scope"() {
        when:
        BuildResult result = repo.runner('pitestA').buildAndFail()

        then:
        result.output.contains('request pitestAll by its full name')
        result.task(':mod-a:pitest') == null
    }

    // Scenario: build-system/build-conventions / Developer runs PIT for one module
    // Scenario: build-system/quality-gates / A one-module run speaks only of its own modules
    // Scenario: build-system/quality-gates / A run that mutates nothing says so
    def "UX2, NFR-O1: a one-module run announces only the modules it mutates, not the branch's other changes"() {
        given: 'a second module, and a change only in the first'
        repo.write('settings.gradle', repo.dir.resolve('settings.gradle').text + "include 'mod-b'\n")
        repo.write('mod-b/build.gradle', "plugins {\n    id 'library-conventions'\n}\n")
        repo.write(FOO, javaClass('Foo', 'public static int twice(int x) { return x + x; }'))

        when:
        BuildResult result = run(':mod-b:pitestVerifyAllKilled')

        then:
        result.task(':mod-a:pitest') == null
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — every module skipped (1)"]
    }
}
