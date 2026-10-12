package com.github.oinsio.gnomish.build

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of the scenario-to-test link of {@code SpecContractCheck} (FR5 of
 * tighten-spec-contract): one feature per scenario of the {@code quality-gates} requirement
 * "Scenario-to-test link", the gate RUN over a miniature repository (TestKit).
 */
class SpecContractScenarioLinkFunctionalSpec extends Specification {

    static final String MARKER = MiniSpecContractProject.MARKER

    @TempDir
    Path dir

    MiniSpecContractProject project

    def setup() {
        project = new MiniSpecContractProject(dir).valid()
    }

    // Scenario: build/quality-gates / A scenario with no marker fails the gate
    def "FR5: a scenario no marker names fails, naming the spec id, the title and the marker to write"() {
        given:
        project.test('DemoSpec', '// no link here')

        expect:
        project.failsReporting("openspec/specs/demo/spec.md:9: [scenario link: unlinked scenario] scenario "
                + "'demo / Works' is named by no test marker and no exemption\n"
                + "    fix: write `${MARKER} demo / Works` above the feature that exercises it")
    }

    // Scenario: build/quality-gates / A dangling marker fails the gate
    def "FR5: a marker naming no scenario fails, naming the test file and line and the nearest title"() {
        given:
        project.test('ExtraSpec', "${MARKER} demo / Work")

        expect:
        project.failsReporting("mod/src/test/groovy/demo/ExtraSpec.groovy:4: [scenario link: dangling marker] "
                + "marker names no scenario of a main spec: 'demo / Work'\n"
                + "    fix: point it at the scenario it exercises — the nearest existing one is 'demo / Works'")
    }

    // Scenario: build/quality-gates / A malformed marker fails the gate
    def "FR5: a marker line not in the spec-id / title form fails, naming the form to write"() {
        given:
        project.test('LooseSpec', "${MARKER} Empty queue — nothing to take")

        expect:
        project.failsReporting("mod/src/test/groovy/demo/LooseSpec.groovy:4: [scenario link: malformed marker] "
                + "a scenario marker not in the link form: '${MARKER} Empty queue — nothing to take'\n"
                + "    fix: write `${MARKER} <spec-id> / <title>`")
    }

    // Scenario: build/quality-gates / A renamed scenario breaks the link on both sides
    def "FR5: a renamed scenario is unlinked and its old marker dangling, in the same run"() {
        given:
        project.spec('demo', MiniSpecContractProject.DEMO_SPEC.replace('Scenario: Works', 'Scenario: Works again'))

        when:
        String output = project.fails().output

        then:
        output.contains("[scenario link: unlinked scenario] scenario 'demo / Works again'")
        output.contains("[scenario link: dangling marker] marker names no scenario of a main spec: 'demo / Works'")
        output.contains("the nearest existing one is 'demo / Works again'")
    }

    // Scenario: build/quality-gates / An exempt scenario passes with its reason
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

    // Scenario: build/quality-gates / A stale exemption fails the gate
    def "FR5: an exemption line naming no scenario of its capability fails, naming the line to remove"() {
        given:
        project.exemptions('demo.txt', 'spec: demo', 'Retired => .github/workflows/ci.yml')

        expect:
        project.failsReporting("config/spec-contract-exemptions/demo.txt:2: [scenario link: exemption] stale exemption "
                + "'demo / Retired': no main spec holds this scenario\n    fix: remove the line")
    }

    // Scenario: build/quality-gates / Scenarios of unarchived deltas are not linked
    def "FR5: a delta scenario with no marker is reported by no run, #flags"() {
        given:
        project.delta('feat', 'demo', '''\
## ADDED Requirements

### Requirement: New thing
The demo SHALL do a new thing.
**Implements**: FR1 of feat

#### Scenario: New thing happens
- **THEN** it happens
''')

        expect:
        project.passes(*flags).output.contains('Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.')

        where:
        flags << [[], ['--changes']]
    }
}
