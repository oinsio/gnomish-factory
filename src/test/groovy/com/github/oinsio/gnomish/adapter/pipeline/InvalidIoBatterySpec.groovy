package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Invalid-fixture battery, tier 4 — the adapter I/O rules and one-pass aggregation:
 * {@code pipeline.yaml} ↔ stage-directory consistency (FR6), referenced-file
 * existence (FR6), and path-traversal rejection (NFR-S2). One minimal
 * {@code .gnomish/} tree per rule asserts the loader returns {@code Invalid} with
 * EXACTLY the expected located {@link ConfigError} set (M2, UX1/UX2), and a final
 * case proves one-pass aggregation across three independent tiers (UX1).
 *
 * <p>Covered: a pipeline stage whose manifest is absent; a dangling stage directory
 * {@code pipeline.yaml} never references; a missing {@code instructions.md}; a
 * missing {@code judge} acceptance-criteria file; and traversal escapes via a
 * {@code ../} instructions path, an absolute judge-criteria path, and a {@code ../}
 * judge-criteria path (each reported as escaping the root only, never also as
 * "does not exist", per the delta-spec scenario).
 *
 * <p>M2 / UX1 / UX2 — FR6, NFR-S2, FR8 of load-pipeline-config.
 */
class InvalidIoBatterySpec extends Specification implements InvalidFixtureSupport {

    @TempDir
    Path root

    Path getRoot() {
        root
    }

    def "M2/FR6: a pipeline stage without a manifest yields exactly its located consistency error"() {
        given: 'pipeline lists plan and build, but only plan has a manifest directory'
        writeValidBaseline()
        write('pipeline.yaml', 'stages:\n  - plan\n  - build\n')

        when: 'build has no DTO, so the model tier is skipped; only the consistency error stands'
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('pipeline.yaml', 'stages[build]', "pipeline stage 'build' has no manifest; expected stages/build/stage.yaml")
        ]
    }

    def "M2/FR6: a dangling stage directory yields exactly its located consistency error"() {
        given: 'pipeline lists only plan, but a structurally-clean orphan directory also exists'
        writeValidBaseline()
        write('stages/orphan/stage.yaml', '''\
purpose: orphan work
executor:
  type: agent-cli
  model: orphan-model
instructions: stages/orphan/instructions.md
advancement: auto
''')
        write('stages/orphan/instructions.md', 'orphan it\n')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/orphan/stage.yaml', 'stages/orphan', "dangling stage directory 'orphan' is not referenced by pipeline.yaml")
        ]
    }

    def "M2/FR6: a missing instructions.md yields exactly its located existence error"() {
        given: 'a structurally clean plan whose instructions file is absent'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', planManifest())

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'instructions', "referenced instructions file 'stages/plan/instructions.md' does not exist")
        ]
    }

    def "M2/FR6: a missing judge acceptance-criteria file yields exactly its located existence error"() {
        given: 'a judge check pointing at an absent acceptance file; instructions exists'
        writeJudge('stages/plan/accept.md')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'verify[0].criteriaFile', "referenced acceptance-criteria file 'stages/plan/accept.md' does not exist")
        ]
    }

    def "M2/NFR-S2: a traversal-escaping reference yields exactly its located escape error, never 'does not exist'"() {
        given:
        writeTraversal(instructionsRef, criteriaRef)

        when:
        def errors = loadInvalid()

        then: 'exactly the escape error; the outside file is never existence-checked'
        errors == [expected]

        where:
        rule                           | instructionsRef                | criteriaRef              || expected
        'instructions ../ escape'      | '../escape.md'                 | ''                       || new ConfigError('stages/plan/stage.yaml', 'instructions', "referenced instructions file '../escape.md' escapes the configuration root")
        'judge criteria absolute path' | 'stages/plan/instructions.md'  | '/etc/passwd'            || new ConfigError('stages/plan/stage.yaml', 'verify[0].criteriaFile', "referenced acceptance-criteria file '/etc/passwd' escapes the configuration root")
        'judge criteria ../ escape'    | 'stages/plan/instructions.md'  | '../secret.md'          || new ConfigError('stages/plan/stage.yaml', 'verify[0].criteriaFile', "referenced acceptance-criteria file '../secret.md' escapes the configuration root")
    }

    /** A valid plan tree whose judge check points criteriaFile at {@code criteria} (file not written). */
    private void writeJudge(String criteria) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', """\
purpose: plan the work
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: judge
    criteriaFile: ${criteria}
    model: judge-model
    votes: 1
advancement: auto
""")
        write('stages/plan/instructions.md', 'plan it\n')
    }

    /** A plan tree with the given instructions and judge-criteria references, to exercise traversal. */
    private void writeTraversal(String instructionsRef, String criteriaRef) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', """\
purpose: plan the work
executor:
  type: agent-cli
  model: plan-model
instructions: ${instructionsRef}
verify:
  - type: judge
    criteriaFile: ${criteriaRef}
    model: judge-model
    votes: 1
advancement: auto
""")
        write('stages/plan/instructions.md', 'plan it\n')
    }

    def "M2/UX1/FR8: three independent problems across tiers are all reported in one pass"() {
        given: 'a missing schemaVersion (domain), an unknown advancement (structural),'
        and: 'and a dangling orphan directory (consistency) — all model-independent of each other'
        write('config.yaml', 'autonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan the work
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
advancement: sideways
''')
        write('stages/plan/instructions.md', 'plan it\n')
        write('stages/orphan/stage.yaml', '''\
purpose: orphan work
executor:
  type: agent-cli
  model: orphan-model
instructions: stages/orphan/instructions.md
advancement: auto
''')
        write('stages/orphan/instructions.md', 'orphan it\n')

        when:
        def errors = loadInvalid()

        then: 'all three located problems appear together (order not asserted — only completeness)'
        errors as Set == [
            new ConfigError('config.yaml', 'schemaVersion', "missing required schemaVersion; supported version is '1'"),
            new ConfigError('stages/plan/stage.yaml', 'advancement', "unknown advancement 'sideways'; known modes are auto, manual"),
            new ConfigError('stages/orphan/stage.yaml', 'stages/orphan', "dangling stage directory 'orphan' is not referenced by pipeline.yaml"),
        ] as Set
    }
}
