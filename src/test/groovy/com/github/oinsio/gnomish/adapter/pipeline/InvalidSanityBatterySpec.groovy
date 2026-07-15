package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Invalid-fixture battery, tier 3 — local mechanism/check sanity (FR11) and the
 * attempt-limit clause (FR7): one minimal {@code .gnomish/} tree per rule, each
 * asserting the loader returns {@code Invalid} with EXACTLY the expected located
 * {@link ConfigError} set (M2, UX1/UX2). These are the pure {@code StageSanityRule}
 * checks: a blank executor {@code model}; a non-positive resolved attempt limit; an
 * {@code external} check with a blank id, a non-positive {@code interval}/
 * {@code timeout}, or {@code interval > timeout}; and a {@code judge} check with a
 * blank {@code model} or a {@code votes} that is even or zero.
 *
 * <p>Each fixture is structurally clean so the model maps and the pure rule runs;
 * {@code judge} rows leave {@code criteriaFile} blank so {@code ReferencedFiles}
 * stays silent (it never resolves a blank reference), keeping the expected set to
 * the single sanity error under test.
 *
 * <p>M2 / UX1 / UX2 — FR11, FR7 of load-pipeline-config.
 */
class InvalidSanityBatterySpec extends Specification implements InvalidFixtureSupport {

    @TempDir
    Path root

    Path getRoot() {
        root
    }

    def "M2/FR11: a blank executor model yields exactly its located sanity error"() {
        given: 'the plan executor pins an empty model'
        writeStage('''\
executor:
  type: api
  model: ""
instructions: stages/plan/instructions.md
''')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor.model', 'missing required executor model; the model must be pinned in the manifest for every executor type')
        ]
    }

    def "M2/FR7: a resolved attempt limit below 1 (default 0, no override) yields exactly its located error"() {
        given: 'config.yaml declares no autonomy default and the stage no override, so it resolves to 0'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', planManifest())
        write('stages/plan/instructions.md', 'plan it\n')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'attempts', 'non-positive resolved attempt limit 0; the resolved limit must be at least 1')
        ]
    }

    def "M2/FR11: external-check timing faults each yield exactly their located error"() {
        given:
        writeStage(verifyBlock)

        when:
        def errors = loadInvalid()

        then:
        errors == [expected]

        where:
        rule                    | verifyBlock                                   || expected
        'blank checkId'         | external('""', '5s', '60s')                   || new ConfigError('stages/plan/stage.yaml', 'verify[0].checkId', 'missing required external check identifier')
        'non-positive interval' | external('ci', '0s', '60s')                   || new ConfigError('stages/plan/stage.yaml', 'verify[0].interval', 'non-positive external poll interval PT0S; the interval must be positive')
        'non-positive timeout'  | external('ci', '5s', '0s')                    || new ConfigError('stages/plan/stage.yaml', 'verify[0].timeout', 'non-positive external poll timeout PT0S; the timeout must be positive')
        'interval over timeout' | external('ci', '2m', '30s')                   || new ConfigError('stages/plan/stage.yaml', 'verify[0].interval', 'external poll interval PT2M exceeds timeout PT30S; the interval must not exceed the timeout')
    }

    def "M2/FR11: judge-check faults each yield exactly their located error"() {
        given:
        writeStage(verifyBlock)

        when:
        def errors = loadInvalid()

        then:
        errors == [expected]

        where:
        rule               | verifyBlock          || expected
        'blank judge model'| judge('""', '3')     || new ConfigError('stages/plan/stage.yaml', 'verify[0].model', 'missing required judge model; the model must be pinned in the manifest for reproducibility')
        'even votes'       | judge('judge-m', '2')|| new ConfigError('stages/plan/stage.yaml', 'verify[0].votes', 'invalid judge vote count 2; votes must be at least 1 and odd')
        'zero votes'       | judge('judge-m', '0')|| new ConfigError('stages/plan/stage.yaml', 'verify[0].votes', 'invalid judge vote count 0; votes must be at least 1 and odd')
    }

    /** A valid single plan-stage tree whose manifest body is the given block (with executor/verify). */
    private void writeStage(String body) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', """\
purpose: plan the work
${body}advancement: auto
""")
        write('stages/plan/instructions.md', 'plan it\n')
    }

    /** An external verify block plus a valid api executor, for the timing rows. */
    private static String external(String checkId, String interval, String timeout) {
        """\
executor:
  type: api
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: external
    checkId: ${checkId}
    interval: ${interval}
    timeout: ${timeout}
"""
    }

    /** A judge verify block (no criteriaFile) plus a valid api executor, for the vote/model rows. */
    private static String judge(String model, String votes) {
        """\
executor:
  type: api
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: judge
    model: ${model}
    votes: ${votes}
"""
    }
}
