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
 * {@code timeout}, or {@code interval > timeout}; and a {@code judge} check whose
 * executor block resolves to no executor serving role {@code verdict} (design D12 of
 * extract-agent-round-runner).
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
  type: agent-cli
  model: ""
instructions: stages/plan/instructions.md
''')

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'executor.model', 'missing required executor model; the model must be pinned in the manifest for every agent executor type (api, agent-cli)')
        ]
    }

    // Scenario: pipeline/pipeline-config / Non-positive attempt limit is rejected
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
        rule | verifyBlock || expected
        'blank checkId' | external('""', '5s', '60s') || new ConfigError('stages/plan/stage.yaml', 'verify[0].checkId', 'missing required external check identifier')
        'non-positive interval' | external('ci', '0s', '60s') || new ConfigError('stages/plan/stage.yaml', 'verify[0].interval', 'non-positive external poll interval PT0S; the interval must be positive')
        'non-positive timeout' | external('ci', '5s', '0s') || new ConfigError('stages/plan/stage.yaml', 'verify[0].timeout', 'non-positive external poll timeout PT0S; the timeout must be positive')
        'interval over timeout' | external('ci', '2m', '30s') || new ConfigError('stages/plan/stage.yaml', 'verify[0].interval', 'external poll interval PT2M exceeds timeout PT30S; the interval must not exceed the timeout')
    }

    // FR13, UX1 of extract-agent-round-runner: a command or api judge block serves no verdict
    def "M2/FR13: a judge block of type #type is a located role error"() {
        given:
        writeStage(judge(block))

        when:
        def errors = loadInvalid()

        then:
        errors == [
            new ConfigError('stages/plan/stage.yaml', 'verify[0].executor.type', reason
            + "; a judge check needs an executor that serves role 'verdict' (type agent-cli, or a program whose describe lists 'verdict')")
        ]

        where:
        type | block || reason
        'command' | '      type: command\n      command: make check\n' || "executor type 'command' resolves to the built-in executor 'command', which serves only role 'transform'"
        'api' | '      type: api\n      model: m\n' || "executor type 'api' resolves to no executor"
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

    /** An external verify block plus a valid agent-cli executor, for the timing rows. */
    private static String external(String checkId, String interval, String timeout) {
        """\
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: external
    checkId: ${checkId}
    interval: ${interval}
    timeout: ${timeout}
"""
    }

    /** A judge verify block (no criteriaFile) with the given executor block lines, plus a valid agent-cli executor. */
    private static String judge(String block) {
        """\
executor:
  type: agent-cli
  model: plan-model
instructions: stages/plan/instructions.md
verify:
  - type: judge
    executor:
${block}"""
    }
}
