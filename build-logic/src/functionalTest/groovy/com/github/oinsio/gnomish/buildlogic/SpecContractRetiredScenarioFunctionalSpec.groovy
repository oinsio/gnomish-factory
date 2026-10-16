package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of scenario retirement in {@code SpecContractCheck} (FR6 and the D7
 * extension of fix-terminal-receipt-convergence): a main-spec requirement that an unarchived delta
 * REMOVES retires its scenarios, and retirement only relaxes the gate. The gate RUN over a
 * miniature repository (TestKit).
 */
class SpecContractRetiredScenarioFunctionalSpec extends Specification {

    static final String MARKER = MiniSpecContractProject.MARKER
    static final String REMOVED_DELTA = MiniSpecContractProject.REMOVED_DELTA

    /** The demo spec with a second requirement, {@code Old thing}, whose scenario is {@code demo / Old thing happens}. */
    static final String SPEC_WITH_OLD_THING = MiniSpecContractProject.DEMO_SPEC + '''
### Requirement: Old thing
The demo SHALL do an old thing.
**Implements**: FR2 of demo-change

#### Scenario: Old thing happens
- **THEN** it happens
'''

    @TempDir
    Path dir

    MiniSpecContractProject project

    def setup() {
        project = new MiniSpecContractProject(dir).valid().spec('demo', SPEC_WITH_OLD_THING)
    }

    // Scenario: build-system/quality-gates / A scenario of a requirement removed by an unarchived delta need not be linked
    def "FR6: a main-spec scenario whose requirement an unarchived delta removes needs no link, #flags"() {
        given:
        project.delta('feat', 'demo', REMOVED_DELTA)

        expect:
        project.passes(*flags).output.contains('Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.')

        where:
        flags << [[], ['--changes']]
    }

    // Scenario: build-system/quality-gates / A scenario of a requirement removed by an unarchived delta need not be linked
    def "FR6: a delta under archive/ that removes the requirement retires nothing"() {
        given:
        project.delta('archive/2026/07/2026-07-14-old', 'demo', REMOVED_DELTA)

        expect:
        project.fails().output.contains("[scenario link: unlinked scenario] scenario 'demo / Old thing happens' "
                + 'is named by no test marker and no exemption')
    }

    // Scenario: build-system/quality-gates / A scenario with no marker fails the gate
    def "FR6: a REMOVED requirement retires only the requirement of its own name in its own spec id"() {
        given:
        project.test('DemoSpec', '// no link here')
                .delta('feat', 'demo', REMOVED_DELTA)
                .delta('feat', 'other', REMOVED_DELTA.replace('Old thing', 'Demo behaves'))

        expect:
        project.fails().output.contains("[scenario link: unlinked scenario] scenario 'demo / Works' "
                + 'is named by no test marker and no exemption')
    }

    // Scenario: build-system/quality-gates / A marker or exemption naming a retired scenario is accepted
    def "FR6: #link naming a retired scenario is neither dangling nor stale, #flags"() {
        given:
        project.delta('feat', 'demo', REMOVED_DELTA)
        if (link == 'a marker') {
            project.test('OldSpec', "${MARKER} demo / Old thing happens")
        } else {
            project.exemptions('demo.txt', 'spec: demo', 'Old thing happens => .github/workflows/ci.yml')
        }

        expect:
        project.passes(*flags).output.contains('Spec contract: 1 scenario(s) checked, 1 linked, 0 exempt.')

        where:
        link            | flags
        'a marker'      | []
        'a marker'      | ['--changes']
        'an exemption'  | []
        'an exemption'  | ['--changes']
    }
}
