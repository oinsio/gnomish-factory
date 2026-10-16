package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code SpecContractCheck}: the gate is RUN over a miniature
 * repository (TestKit), one feature per scenario of the {@code quality-gates} delta of
 * tighten-spec-contract. The scenario-to-test link has its own suite,
 * {@code SpecContractScenarioLinkFunctionalSpec}, the callers theirs,
 * {@code SpecContractLauncherFunctionalSpec}.
 */
class SpecContractCheckFunctionalSpec extends Specification {

    @TempDir
    Path dir

    MiniSpecContractProject project

    def setup() {
        project = new MiniSpecContractProject(dir).valid()
    }

    // Scenario: build-system/quality-gates / A requirement without the line fails the gate
    def "FR1: a requirement without an Implements line names the file, the heading and the line to add"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('**Implements**: FR1 of demo-change\n', ''))

        expect:
        project.failsReporting("openspec/specs/demo/spec.md:5: [traceability line] requirement 'Demo behaves' "
                + 'has no **Implements**: line\n    fix: add `**Implements**: <ids> of <change-name>')
    }

    // Scenario: build-system/quality-gates / A retired comment form fails the gate
    def "FR1: the retired comment form fails in a main spec and in an unarchived delta"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('**Implements**', '<!-- implements FR1 -->\n**Implements**'))
                .delta('feat', 'demo', '## ADDED Requirements\n\n### Requirement: New\nText.\n<!-- implements FR2 of feat -->\n')

        when:
        String output = project.fails('--changes').output

        then:
        output.contains("openspec/specs/demo/spec.md:7: [retired comment form] retired traceability comment: '<!-- implements FR1 -->'")
        output.contains("openspec/changes/feat/specs/demo/spec.md:5: [retired comment form]")
        output.contains('fix: delete the comment and carry its ids')
    }

    // Scenario: build-system/quality-gates / Two traceability lines fail the gate
    def "FR1: two Implements lines ask for one line joined by a semicolon"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('demo-change\n', 'demo-change\n**Implements**: FR2 of other\n'))

        expect:
        project.failsReporting("spec.md:8: [traceability line] requirement 'Demo behaves' carries a second "
                + "**Implements**: line: '**Implements**: FR2 of other'\n    fix: keep one **Implements**: line, the ids of every change joined by `;`")
    }

    // Scenario: build-system/quality-gates / Archived deltas are not read
    def "FR1: a comment form under the archive is never read, even with --changes"() {
        given:
        project.delta('archive/2026/07/2026-07-14-old', 'demo', '### Requirement: Old\nText.\n<!-- implements FR1 of old -->\n')

        expect:
        project.passes('--changes').output.contains('Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.')
    }

    // Scenario: build-system/quality-gates / A note with a normative word fails the gate
    def "FR2: a note carrying #word asks for a scenario or a requirement instead"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('demo-change\n', "demo-change\n**Notes**: it ${word} hold.\n"))

        expect:
        project.failsReporting("spec.md:8: [notes] a note carries a normative word: '**Notes**: it ${word} hold.'\n"
                + '    fix: state the obligation as a scenario of this requirement, or as a requirement of its own')

        where:
        word << ['SHALL', 'MUST']
    }

    // Scenario: build-system/quality-gates / A wrapped note fails the gate
    def "FR2: ordinary text after a Notes line is reported as a wrapped note"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('demo-change\n', 'demo-change\n**Notes**: one line\nthat wraps.\n'))

        expect:
        project.failsReporting("spec.md:9: [wrapped note] ordinary text directly after a metadata line: 'that wraps.'")
    }

    // Scenario: build-system/quality-gates / Several one-line notes pass
    def "FR2: three consecutive one-line notes without a normative word pass"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('demo-change\n',
                'demo-change\n**Notes**: first, shall in lower case.\n**Notes**: second.\n**Notes**: third, MUSTARD.\n'))

        expect:
        project.passes()
    }

    // Scenario: build-system/quality-gates / Findings read like the other gates
    def "UX1: every finding names file, line, rule and fix, and the run ends with one summary line, no stack trace"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('**Implements**: FR1 of demo-change\n', ''))
                .test('DemoSpec', "${MiniSpecContractProject.MARKER} demo / Gone")

        when:
        String output = project.fails().output
        String report = project.report()

        then: 'the report holds each finding as rendered; the console shows the same, indented by Gradle'
        report =~ /(?m)^openspec\/specs\/demo\/spec\.md:5: \[traceability line\] .+\n {4}fix: .+$/
        report =~ /(?m)^mod\/src\/test\/groovy\/demo\/DemoSpec\.groovy:4: \[scenario link: dangling marker\] .+\n {4}fix: .+$/
        report.readLines().last() == 'Spec contract: 1 scenario(s) checked, 0 linked, 0 exempt.'
        output.contains('openspec/specs/demo/spec.md:5: [traceability line] ')
        output.contains('rules are in .claude/rules/traceability.md and .claude/rules/delta-specs.md.\n'
                + '  Spec contract: 1 scenario(s) checked, 0 linked, 0 exempt.')
        !output.contains('\tat ')
    }

    // Scenario: build-system/quality-gates / Spec and test text is never executed
    def "NFR-S1: shell-like text in a note and a marker is quoted verbatim and never run"() {
        given:
        String note = '**Notes**: $(touch pwned) and `touch pwned` | tee pwned MUST stay text.'
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('demo-change\n', "demo-change\n${note}\n"))
                .test('ShellSpec', "${MiniSpecContractProject.MARKER} demo / \$(touch pwned) `id` | sh")

        when:
        String output = project.fails().output

        then:
        output.contains("'${note}'")
        output.contains("'demo / \$(touch pwned) `id` | sh'")
        !Files.exists(dir.resolve('pwned'))
    }

    // Scenario: build-system/quality-gates / The gate is cacheable
    def "NFR-R1: a second run over unchanged trees is up to date"() {
        given:
        project.passes()

        expect:
        project.passes().task(':checkSpecContract').outcome.name() == 'UP_TO_DATE'
    }

    def "FR5: the spec filter judges only the named spec ids, and specsDir reads another tree"() {
        given: 'a broken spec outside the filter, and a previewed tree holding the valid one'
        project.spec('other', MiniSpecContractProject.DEMO_SPEC.replace('**Implements**: FR1 of demo-change\n', ''))
        project.configure("    specsDir = layout.projectDirectory.dir('preview')")
        GradleRunnerSupport.writeFile(dir, 'preview/demo/spec.md', MiniSpecContractProject.DEMO_SPEC)

        expect: 'the preview tree has no broken spec; the real tree is scoped by the filter'
        project.passes().output.contains('1 scenario(s) checked, 1 linked')
        project.configure('').passes('--spec=demo').output.contains('1 scenario(s) checked, 1 linked')
        project.failsReporting('openspec/specs/other/spec.md:5: [traceability line]')
    }

    // Scenario: build-system/quality-gates / Root check runs the gate
    def "FR6: root check fails in checkSpecContract before a module's mutation task, even one named first"() {
        given: 'the root build as shipped, and a module whose check runs a mutation task'
        project.conventions().spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('**Implements**: FR1 of demo-change\n', ''))
        GradleRunnerSupport.writeFile(dir, 'settings.gradle', "rootProject.name = 'mini-spec-contract'\ninclude 'mod'\n")
        GradleRunnerSupport.writeFile(dir, 'mod/build.gradle', "plugins { id 'base' }\n"
                + "def pitest = tasks.register('pitest') { doLast { println 'MUTATION RAN' } }\n"
                + "tasks.named('check') { dependsOn pitest }\n")

        when: 'a mutation task is requested ahead of check'
        BuildResult result = project.commandLine([':mod:pitest', 'check']).buildAndFail()

        then:
        result.output.contains("Execution failed for task ':checkSpecContract'")
        result.output.contains('openspec/specs/demo/spec.md:5: [traceability line]')
        result.task(':mod:pitest') == null
        !result.output.contains('MUTATION RAN')
    }
}
