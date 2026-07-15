package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Invalid-fixture battery, tier 2 — stage order (FR3) and the artifact DAG (FR3,
 * FR4, FR6): one minimal {@code .gnomish/} tree per rule, each asserting the loader
 * returns {@code Invalid} with EXACTLY the expected located {@link ConfigError} set
 * (M2, UX1/UX2). These are the pure domain rules {@code StageOrderRule} and
 * {@code ArtifactGraphRule}, which run only once a model maps — so each fixture is
 * kept structurally clean and every referenced {@code instructions.md} exists, and
 * the duplicate-name / DAG fixtures declare only the outputs the rule under test
 * needs, so no sibling rule fires alongside it.
 *
 * <p>Covered: empty stage list; duplicate stage name; duplicate output id; a
 * dangling internal reference (no producer); a forward reference (produced by a
 * later stage); and a self reference.
 *
 * <p>M2 / UX1 / UX2 — FR3, FR4, FR6 of load-pipeline-config.
 */
class InvalidOrderDagBatterySpec extends Specification implements InvalidFixtureSupport {

    @TempDir
    Path root

    Path getRoot() {
        root
    }

    def "M2/FR3: an empty pipeline stage list yields exactly the located order error"() {
        given: 'config.yaml is valid; pipeline.yaml declares an empty list (the model still maps)'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages: []\n')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('pipeline.yaml', 'stages', 'pipeline declares no stages')
        ]
    }

    def "M2/FR6: a duplicate stage name yields exactly the located uniqueness error"() {
        given: 'pipeline.yaml lists plan twice; plan declares no output, so only the name rule fires'
        writeValidBaseline()
        write('pipeline.yaml', 'stages:\n  - plan\n  - plan\n')

        when:
        def errors = loadInvalid()

        then: 'one error naming the stage and its occurrence count (reported once, not per copy)'
        errors == [
            new ConfigError('pipeline.yaml', 'stages[plan]', "duplicate stage name 'plan' declared 2 times; stage names must be unique")
        ]
    }

    def "M2/FR4/FR6: a duplicate output id yields exactly the located DAG error"() {
        given: 'two stages both declaring the output id dup; nothing references it, so only the id rule fires'
        writeTwoStageTree('''\
outputs:
  - id: dup
''', '''\
outputs:
  - id: dup
''')

        when:
        def errors = loadInvalid()

        then: 'one error located at the first declaring manifest, naming both stages'
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'outputs[dup]', "duplicate output id 'dup' declared 2 times by stages 'plan', 'build'; output ids must be unique across the pipeline")
        ]
    }

    def "M2/FR4: a dangling internal reference (no producer) yields exactly its located error"() {
        given: 'plan references an id no stage produces'
        writeSingleStageWith('''\
inputs:
  - kind: internal
    producerOutputId: ghost
''')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'inputs[ghost]', "internal input references output id 'ghost', which no stage produces")
        ]
    }

    def "M2/FR4: a self reference yields exactly its located error"() {
        given: 'plan both produces and consumes selfie'
        writeSingleStageWith('''\
inputs:
  - kind: internal
    producerOutputId: selfie
outputs:
  - id: selfie
''')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'inputs[selfie]', "internal input references output id 'selfie', which is produced by stage 'plan' itself; the producer must be an earlier stage")
        ]
    }

    def "M2/FR4: a forward reference (produced by a later stage) yields exactly its located error"() {
        given: 'plan consumes late, which the later build stage produces'
        writeTwoStageTree('''\
inputs:
  - kind: internal
    producerOutputId: late
''', '''\
outputs:
  - id: late
''')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'inputs[late]', "internal input references output id 'late', which is first produced by later stage 'build'; the producer must be an earlier stage")
        ]
    }

    /** A valid single-stage tree whose plan manifest carries the given extra section(s). */
    private void writeSingleStageWith(String extra) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', stageManifest('plan', extra))
        write('stages/plan/instructions.md', 'plan it\n')
    }

    /** A valid two-stage plan->build tree, each manifest carrying the given extra section(s). */
    private void writeTwoStageTree(String planExtra, String buildExtra) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n  - build\n')
        write('stages/plan/stage.yaml', stageManifest('plan', planExtra))
        write('stages/plan/instructions.md', 'plan it\n')
        write('stages/build/stage.yaml', stageManifest('build', buildExtra))
        write('stages/build/instructions.md', 'build it\n')
    }

    /** A structurally clean manifest for {@code name} with an injected extra block. */
    private static String stageManifest(String name, String extra) {
        """\
purpose: ${name} the work
${extra}executor:
  type: api
  model: ${name}-model
instructions: stages/${name}/instructions.md
advancement: auto
"""
    }
}
