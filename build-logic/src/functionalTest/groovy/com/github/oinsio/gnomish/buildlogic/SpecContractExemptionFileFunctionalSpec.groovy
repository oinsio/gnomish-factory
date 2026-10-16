package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of the exemption files of {@code SpecContractCheck} (FR5 of
 * tighten-spec-contract, design D5, D9): one file per capability under
 * {@code config/spec-contract-exemptions}, its header {@code spec: <spec-id>} naming the capability
 * and its name that spec id with {@code /} replaced by {@code -}. Each defect of a file is a finding
 * naming the file and the line; the gate RUN over a miniature repository (TestKit).
 */
class SpecContractExemptionFileFunctionalSpec extends Specification {

    static final String PREFIX = 'config/spec-contract-exemptions/'

    @TempDir
    Path dir

    MiniSpecContractProject project

    def setup() {
        project = new MiniSpecContractProject(dir).valid()
        project.spec('build-system/ci', MiniSpecContractProject.DEMO_SPEC.replace('# demo', '# build-system/ci'))
                .test('CiSpec', '// verified by CI')
    }

    def "FR5: a defective line of an exemption file fails, naming the file, the line and what to write"() {
        given:
        project.exemptions('build-system-ci.txt', 'spec: build-system/ci', *lines)

        expect:
        project.failsReporting("${PREFIX}build-system-ci.txt:${line}: [scenario link: exemption] ${what}\n    fix: ${fix}")

        where:
        lines                                          | line | what                                                             | fix
        ['Works .github/workflows/ci.yml']             | 2    | "not an exemption entry: 'Works .github/workflows/ci.yml'"       | 'write `<scenario title> => <mechanism>`'
        [' => .github/workflows/ci.yml']               | 2    | "not an exemption entry: '=> .github/workflows/ci.yml'"          | 'write `<scenario title> => <mechanism>`'
        ['Works =>  ']                                 | 2    | "not an exemption entry: 'Works =>'"                             | 'write `<scenario title> => <mechanism>`'
        ['Works => pending-test: Add_Later']           | 2    | "exemption 'Works' names no verifier: 'pending-test: Add_Later'" | 'name the mechanism'
        ['Works => ci.yml', '', 'Works => release.sh'] | 4    | "duplicate exemption 'Works', first at line 2"                   | 'remove one of the two lines'
    }

    def "FR5: an exemption file whose name is not its declared spec id with / replaced by - fails"() {
        given:
        project.exemptions('ci.txt', 'spec: build-system/ci', 'Works => .github/workflows/ci.yml')

        expect:
        project.failsReporting("${PREFIX}ci.txt:1: [scenario link: exemption] the file declares spec id 'build-system/ci' "
                + "but is named 'ci.txt'\n    fix: rename the file to `build-system-ci.txt`, or correct the header")
    }

    def "FR5: an exemption file naming no capability, or one no main spec has, fails"() {
        given:
        project.exemptions(name, *lines)

        expect:
        project.failsReporting("${PREFIX}${where}: [scenario link: exemption] ${what}")

        where:
        name          | lines                                | where           | what
        'demo.txt'    | ['# only a comment']                 | 'demo.txt'      | 'the file names no capability'
        'demo.txt'    | ['Works => ci.yml']                  | 'demo.txt:1'    | "the first line is not a header naming a capability: 'Works => ci.yml'"
        'retired.txt' | ['spec: retired', 'Works => ci.yml'] | 'retired.txt:1' | "unknown spec id 'retired': no main spec has it"
        'demo.md'     | ['spec: demo']                       | 'demo.md'       | "'demo.md' is not an exemption file"
    }

    def "FR5: a scoped run judges only the in-scope files' lines, and every file's header"() {
        given:
        project.exemptions('demo.txt', 'spec: demo', 'Retired => ci.yml')
                .exemptions('build-system-ci.txt', 'spec: build-system/ci', 'Works => ci.yml')

        expect:
        project.passes('--spec=build-system/ci').output.contains('1 scenario(s) checked, 0 linked, 1 exempt.')
        project.exemptions('stray.txt', 'Works => ci.yml').fails('--spec=build-system/ci').output
                .contains("${PREFIX}stray.txt:1: [scenario link: exemption] the first line is not a header")
    }

    def "FR5: the exemption directory is an input — unchanged it is up to date, an edited file reruns the gate"() {
        given:
        project.exemptions('build-system-ci.txt', 'spec: build-system/ci', 'Works => .github/workflows/ci.yml').passes()

        expect:
        project.passes().task(':checkSpecContract').outcome == TaskOutcome.UP_TO_DATE
        project.exemptions('build-system-ci.txt', 'spec: build-system/ci', 'Works => pending-test: add-later')
                .passes().output.contains('2 scenario(s) checked, 1 linked, 1 exempt (1 pending-test).')
    }
}
