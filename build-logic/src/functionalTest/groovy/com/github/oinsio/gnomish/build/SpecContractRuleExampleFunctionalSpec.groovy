package com.github.oinsio.gnomish.build

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The example {@code .claude/rules/delta-specs.md} shows an author is a spec the gate accepts
 * (FR7, UX3 of tighten-spec-contract): the first fenced Markdown block holding a requirement is
 * read from the REAL rule file, dropped into a miniature repository as a main spec and as an
 * unarchived delta, each of its scenarios given a marker, and {@code checkSpecContract} runs
 * over both. A template that loses its traceability line, wraps its note or puts a normative
 * word into it turns this red.
 */
class SpecContractRuleExampleFunctionalSpec extends Specification {

    @TempDir
    Path dir

    def "FR7, UX3: the delta-specs.md example passes checkSpecContract as a main spec and as a delta"() {
        given: 'the example, with the parts the rule promises'
        String example = ruleExample()
        List<String> titles = (example =~ /(?m)^#### Scenario:(.+)$/).collect { it[1].trim() }
        assert example.contains('**Implements**:') && example.contains('**Notes**:') && !titles.isEmpty()

        and: 'a valid tree holding it, every scenario linked'
        MiniSpecContractProject project = new MiniSpecContractProject(dir).valid()
                .spec('example', example)
                .delta('feat', 'example', example)
                .test('ExampleSpec', *titles.collect { "${MiniSpecContractProject.MARKER} example / ${it}".toString() })

        expect:
        project.passes('--changes').output
                .contains("Spec contract: ${titles.size() + 1} scenario(s) checked, ${titles.size() + 1} linked, 0 exempt.")
    }

    private static String ruleExample() {
        String rule = new File(System.getProperty('gnomish.deltaSpecsRule')).getText('UTF-8')
        List<String> blocks = (rule =~ /(?ms)^```markdown\n(.*?)^```$/).collect { it[1] as String }
        blocks.find { it.contains('### Requirement:') }
    }
}
