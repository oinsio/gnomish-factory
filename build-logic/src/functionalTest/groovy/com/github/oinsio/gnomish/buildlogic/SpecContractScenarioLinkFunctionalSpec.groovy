package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of the scenario-to-test link of {@code SpecContractCheck} (FR5 of
 * tighten-spec-contract, FR6 of fix-terminal-receipt-convergence): one feature per scenario of the
 * {@code quality-gates} requirement "Scenario-to-test link", the gate RUN over a miniature
 * repository (TestKit).
 */
class SpecContractScenarioLinkFunctionalSpec extends Specification {

    static final String MARKER = MiniSpecContractProject.MARKER
    static final String ADDED_DELTA = MiniSpecContractProject.ADDED_DELTA
    static final String MODIFIED_SECTION = MiniSpecContractProject.MODIFIED_SECTION
    static final String REMOVED_DELTA = MiniSpecContractProject.REMOVED_DELTA

    @TempDir
    Path dir

    MiniSpecContractProject project

    def setup() {
        project = new MiniSpecContractProject(dir).valid()
    }

    // Scenario: build-system/quality-gates / A scenario with no marker fails the gate
    def "FR5: a scenario no marker names fails, naming the spec id, the title and the marker to write"() {
        given:
        project.test('DemoSpec', '// no link here')

        expect:
        project.failsReporting("openspec/specs/demo/spec.md:9: [scenario link: unlinked scenario] scenario "
                + "'demo / Works' is named by no test marker and no exemption\n"
                + "    fix: write `${MARKER} demo / Works` above the feature that exercises it")
    }

    // Scenario: build-system/quality-gates / A dangling marker fails the gate
    def "FR5, FR6: a marker naming no main-spec or delta scenario fails, naming the test file and line and the nearest title"() {
        given:
        project.delta('feat', 'demo', ADDED_DELTA)
                .test('ExtraSpec', "${MARKER} demo / Work")

        expect:
        project.failsReporting("mod/src/test/groovy/demo/ExtraSpec.groovy:4: [scenario link: dangling marker] "
                + "marker names no scenario of a main spec or of an unarchived delta: 'demo / Work'\n"
                + "    fix: point it at the scenario it exercises — the nearest existing one is 'demo / Works'")
    }

    // Scenario: build-system/quality-gates / A marker naming an unarchived delta's scenario is not dangling
    def "FR6: markers naming scenarios of ADDED and MODIFIED delta requirements pass, #flags"() {
        given:
        project.delta('feat', 'demo', ADDED_DELTA + MODIFIED_SECTION)
                .test('NewSpec', "${MARKER} demo / New thing happens", "${MARKER} demo / Works twice")

        expect:
        project.passes(*flags).output.contains('Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.')

        where:
        flags << [[], ['--changes']]
    }

    // Scenario: build-system/quality-gates / A marker naming a removed or archived scenario is dangling
    def "FR6: a marker naming a scenario only a REMOVED requirement or an archived delta holds is dangling"() {
        given:
        project.delta('feat', 'demo', REMOVED_DELTA)
                .delta('archive/2026/07/2026-07-14-old', 'demo', ADDED_DELTA)
                .test('GoneSpec', "${MARKER} demo / Old thing happens", "${MARKER} demo / New thing happens")

        when:
        String output = project.fails().output

        then:
        output.contains("GoneSpec.groovy:4: [scenario link: dangling marker] "
                + "marker names no scenario of a main spec or of an unarchived delta: 'demo / Old thing happens'")
        output.contains("GoneSpec.groovy:5: [scenario link: dangling marker] "
                + "marker names no scenario of a main spec or of an unarchived delta: 'demo / New thing happens'")
    }

    def "FR6: the unarchived deltas are an input of the gate, so a delta that drops a scenario reruns it red"() {
        given:
        project.delta('feat', 'demo', ADDED_DELTA)
                .test('NewSpec', "${MARKER} demo / New thing happens")
        project.passes()

        when:
        project.delta('feat', 'demo', REMOVED_DELTA.replace('Old thing', 'New thing'))

        then:
        project.fails().output.contains("marker names no scenario of a main spec or of an unarchived delta: "
                + "'demo / New thing happens'")
    }

    // Scenario: build-system/quality-gates / A malformed marker fails the gate
    def "FR5: a marker line not in the spec-id / title form fails, naming the form to write"() {
        given:
        project.test('LooseSpec', "${MARKER} Empty queue — nothing to take")

        expect:
        project.failsReporting("mod/src/test/groovy/demo/LooseSpec.groovy:4: [scenario link: malformed marker] "
                + "a scenario marker not in the link form: '${MARKER} Empty queue — nothing to take'\n"
                + "    fix: write `${MARKER} <spec-id> / <title>`")
    }

    // Scenario: build-system/quality-gates / A renamed scenario breaks the link on both sides
    def "FR5: a renamed scenario is unlinked and its old marker dangling, in the same run"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('Scenario: Works', 'Scenario: Works again'))

        when:
        String output = project.fails().output

        then:
        output.contains("[scenario link: unlinked scenario] scenario 'demo / Works again'")
        output.contains("[scenario link: dangling marker] marker names no scenario of a main spec or of an unarchived delta: "
                + "'demo / Works'")
        output.contains("the nearest existing one is 'demo / Works again'")
    }

    // Scenario: build-system/quality-gates / An exempt scenario passes with its reason
    // Scenario: build-system/quality-gates / An exemption entry names its mechanism
    def "FR5: a scenario its capability's exemption file names with its verifying workflow passes"() {
        given:
        project.test('DemoSpec', '// verified by CI')
                .exemptions('demo.txt', '# verified outside a test', '', 'spec: demo', 'Works => .github/workflows/ci.yml')

        expect:
        project.passes().output.contains('Spec contract: 1 scenario(s) checked, 0 linked, 1 exempt.')
    }

    def "FR5, D9: a pending-test exemption names its change, and a verifier of no form is a finding"() {
        given:
        project.test('DemoSpec', '// test pending')

        expect:
        project.exemptions('demo.txt', 'spec: demo', 'Works => pending-test: add-later')
                .passes().output.contains('0 linked, 1 exempt (1 pending-test).')
        project.exemptions('demo.txt', 'spec: demo', 'Works => pending-test:').fails().output.contains(
                "config/spec-contract-exemptions/demo.txt:2: [scenario link: exemption] exemption 'Works' names no verifier")
    }

    // Scenario: build-system/quality-gates / A stale exemption fails the gate
    // Scenario: build-system/quality-gates / An entry naming no scenario fails the gate
    def "FR5: an exemption line naming no scenario of its capability fails, naming the line to remove"() {
        given:
        project.exemptions('demo.txt', 'spec: demo', 'Retired => .github/workflows/ci.yml')

        expect:
        project.failsReporting("config/spec-contract-exemptions/demo.txt:2: [scenario link: exemption] stale exemption "
                + "'demo / Retired': no main spec holds this scenario\n    fix: remove the line")
    }

    // Scenario: build-system/quality-gates / Scenarios of unarchived deltas are not linked
    def "FR5: a delta scenario with no marker is reported by no run, #flags"() {
        given:
        project.delta('feat', 'demo', ADDED_DELTA)

        expect:
        project.passes(*flags).output.contains('Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.')

        where:
        flags << [[], ['--changes']]
    }
}
