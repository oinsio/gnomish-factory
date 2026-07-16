package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * PipelineLoader is the composition point of the whole capability (task 6.5, FR1/FR8):
 * given a .gnomish/ root Path it reads the tree (GnomishFiles), parses each file
 * structurally (StructuralParse), captures shape problems (StructuralValidation),
 * reconciles pipeline.yaml with the stage directories (StageConsistency), maps the
 * structurally-valid DTOs into the pure domain model (PipelineMapper), then runs the
 * pure semantic rules (PipelineValidator) and the I/O checks (ReferencedFiles),
 * aggregating every located ConfigError from every tier into one LoadOutcome.
 *
 * <p>Exception contract (design D3, FR8): validation problems are data, returned as
 * LoadOutcome.Invalid; only a genuine I/O fault — an unreadable required file — escapes
 * as an IOException. A tree with problems in multiple independent tiers surfaces all of
 * them where dependencies allow (one-pass, UX1); a later tier that depends on a mapped
 * model is skipped only when its input could not be produced (layered short-circuit, D6).
 *
 * <p>No execution (NFR-S1) and no writes (NFR-R1): the loader only parses and validates
 * — it runs no command, model, or external check, and never touches the tree on disk.
 * With nothing executed, loading makes no model or network call (NFR-C1: zero token cost).
 * Implements FR1, FR8 (+ NFR-S1, NFR-R1, NFR-C1) of load-pipeline-config.
 */
class PipelineLoaderSpec extends Specification {

    @TempDir
    Path root

    private void write(String relative, String text) {
        Path target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    /** Writes a complete, structurally- and semantically-valid two-stage tree. */
    private void writeValidTree() {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - plan\n  - build\n')
        write('stages/plan/stage.yaml', planManifest())
        write('stages/plan/instructions.md', 'plan it\n')
        write('stages/plan/accept.md', 'criteria\n')
        write('stages/build/stage.yaml', buildManifest())
        write('stages/build/instructions.md', 'build it\n')
    }

    private static String planManifest() {
        '''\
purpose: plan the work
outputs:
  - id: plan-doc
executor:
  type: api
  model: some-model
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: echo ok
  - type: judge
    criteriaFile: stages/plan/accept.md
    model: judge-model
    votes: 1
advancement: auto
'''
    }

    private static String buildManifest() {
        '''\
purpose: build the work
inputs:
  - kind: internal
    producerOutputId: plan-doc
  - kind: source
outputs:
  - id: build-artifact
executor:
  type: agent-cli
  model: cli-model
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
  - type: external
    checkId: ci
    interval: 5s
    timeout: 60s
advancement: manual
'''
    }

    def "a fully valid tree loads into a PipelineDefinition with the expected stages in order"() {
        given:
        writeValidTree()

        when:
        def outcome = PipelineLoader.load(root)

        then: 'a Loaded outcome carrying the model in pipeline.yaml order'
        outcome instanceof LoadOutcome.Loaded
        def model = (outcome as LoadOutcome.Loaded).definition()
        model.schemaVersion() == '1'
        model.stages()*.name() == ['plan', 'build']

        and: 'the resolved model reflects the manifests (light check — 7.1 asserts field-by-field)'
        model.stages()[0].advancement() == AdvancementMode.AUTO
        model.stages()[1].advancement() == AdvancementMode.MANUAL
        model.stages()[0].executor().type() == ExecutorType.API
        model.stages()[1].executor().type() == ExecutorType.AGENT_CLI
        model.stages()[1].limits().attemptLimit() == 3
    }

    def "an unreadable required file is an I/O fault (IOException), not a ConfigError"() {
        given: 'a tree with no config.yaml at all'
        write('pipeline.yaml', 'stages:\n  - plan\n')

        when:
        PipelineLoader.load(root)

        then: 'the missing required file surfaces as an exception, never Invalid (FR8/D3)'
        thrown(IOException)
    }

    def "a malformed top-level file surfaces its parse error in the aggregate"() {
        given: 'config.yaml is malformed YAML; pipeline.yaml is well-formed'
        write('config.yaml', 'foo: [unclosed\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')

        when:
        def outcome = PipelineLoader.load(root)

        then: "config.yaml's parse error is aggregated (collectParse contributes it)"
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'config.yaml' && it.message().contains('malformed YAML')
        }
    }

    def "a malformed pipeline.yaml surfaces its parse error and skips its shape check"() {
        given: 'pipeline.yaml is malformed YAML'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages: [unclosed\n')

        when:
        def outcome = PipelineLoader.load(root)

        then: "pipeline.yaml's parse error is aggregated"
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'pipeline.yaml' && it.message().contains('malformed YAML')
        }
    }

    def "a well-formed pipeline.yaml missing its stages key is reported by the structural tier"() {
        given: 'pipeline.yaml parses into a DTO but omits the required stages key'
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', '{}\n')

        when:
        def outcome = PipelineLoader.load(root)

        then: 'the structural checkPipeline error is aggregated (guard runs on the parsed-OK DTO)'
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'pipeline.yaml' && it.where() == 'stages' &&
            it.message() == "missing required field 'stages'"
        }
    }

    def "a pipeline stage with no manifest skips the model tier: no empty-pipeline domain error appears"() {
        given: 'pipeline names one stage whose manifest is absent, so the model cannot be built'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')

        when: 'orderedEntries returns null (not empty), so mapping and the domain tier are skipped'
        def outcome = PipelineLoader.load(root)

        then: 'only the consistency error is present — no domain empty-pipeline error is fabricated'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.any { it.file() == 'pipeline.yaml' && it.message().contains("stage 'plan' has no manifest") }
        // If orderedEntries returned an empty list instead of null, the mapper would build an
        // empty model and StageOrderRule would emit "pipeline declares no stages": it must not.
        !errors.any { it.message().contains('declares no stages') }
    }

    def "a tree with independent problems across model-independent tiers reports them all in one pass"() {
        given: 'an unknown executor (structural), a pipeline stage without a manifest and'
        and: 'a dangling stage directory (consistency) — all reportable without a mapped model'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - plan\n  - ghost\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan
executor:
  type: bogus
  model: m
instructions: stages/plan/instructions.md
advancement: auto
''')
        write('stages/plan/instructions.md', 'plan\n')
        write('stages/orphan/stage.yaml', 'purpose: x\n')

        when:
        def outcome = PipelineLoader.load(root)

        then: 'Invalid, carrying every independent problem at once (UX1)'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()

        and: 'structural: unknown executor on plan'
        errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.where() == 'executor.type' &&
            it.message().contains("unknown executor 'bogus'")
        }

        and: 'consistency: ghost has no manifest, orphan is dangling'
        errors.any { it.file() == 'pipeline.yaml' && it.message().contains("stage 'ghost' has no manifest") }
        errors.any { it.file() == 'stages/orphan/stage.yaml' && it.message().contains("dangling stage directory 'orphan'") }
    }

    def "domain rules run when a model maps: an empty pipeline and missing version are both reported"() {
        given: 'config.yaml has no schemaVersion and pipeline.yaml declares an empty stage list'
        write('config.yaml', 'autonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages: []\n')

        when: 'the model maps (empty stages qualifies), so the pure domain tier runs (D6)'
        def outcome = PipelineLoader.load(root)

        then: 'Invalid, carrying both domain problems in one pass'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.any { it.file() == 'config.yaml' && it.message().toLowerCase().contains('version') }
        errors.any { it.file() == 'pipeline.yaml' && it.message().contains('declares no stages') }
    }

    def "a file that will not parse short-circuits only its own semantic checks; other files still report"() {
        given: "plan's manifest is malformed YAML; build's is well-formed but structurally invalid"
        write('config.yaml', 'schemaVersion: "1"\n')
        write('pipeline.yaml', 'stages:\n  - plan\n  - build\n')
        write('stages/plan/stage.yaml', 'purpose: "unterminated\n')
        write('stages/build/stage.yaml', '''\
purpose: build
executor:
  type: nonsense
  model: m
instructions: stages/build/instructions.md
advancement: auto
''')
        write('stages/build/instructions.md', 'build\n')

        when:
        def outcome = PipelineLoader.load(root)

        then:
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()

        and: "plan's malformed YAML is reported once, and its shape checks are NOT run"
        errors.any { it.file() == 'stages/plan/stage.yaml' && it.message().contains('malformed YAML') }
        !errors.any { it.file() == 'stages/plan/stage.yaml' && it.where() == 'executor.type' }

        and: "build's structural error is still reported (other files proceed)"
        errors.any { it.file() == 'stages/build/stage.yaml' && it.where() == 'executor.type' }
    }

    def "a missing referenced file is reported via the I/O tier when the model maps cleanly"() {
        given: 'everything valid except the build instructions file is absent'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/stage.yaml', '''\
purpose: build
executor:
  type: api
  model: m
instructions: stages/build/instructions.md
advancement: auto
''')

        when:
        def outcome = PipelineLoader.load(root)

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'stages/build/stage.yaml' && it.where() == 'instructions' &&
            it.message().contains('does not exist')
        }
    }

    def "loading is deterministic: the same tree yields an equal outcome twice (NFR-R1)"() {
        given:
        writeValidTree()

        expect:
        PipelineLoader.load(root) == PipelineLoader.load(root)
    }

    def "loading never creates, modifies, or deletes anything under the root (NFR-R1)"() {
        given: 'a valid tree and a snapshot of every file before loading'
        writeValidTree()
        def before = snapshot(root)

        when:
        PipelineLoader.load(root)

        then: 'the tree is byte-for-byte unchanged'
        snapshot(root) == before
    }

    def "loading executes no configured command (NFR-S1, NFR-C1): a destructive command leaves no trace"() {
        // NFR-C1: with no command, model, or external check ever run, loading
        // makes no model or network call — zero token cost.
        given: 'a stage whose command would create a sentinel file if it ever ran'
        def sentinel = root.resolve('sentinel.txt')
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 1\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/instructions.md', 'plan\n')
        write("stages/plan/stage.yaml", """\
purpose: plan
executor:
  type: api
  model: m
instructions: stages/plan/instructions.md
verify:
  - type: command
    command: touch ${sentinel}
advancement: auto
""")

        when: 'the tree loads successfully'
        def outcome = PipelineLoader.load(root)

        then: 'the command was carried as inert data, not executed — the sentinel never appeared'
        outcome instanceof LoadOutcome.Loaded
        !Files.exists(sentinel)
    }

    def "the aggregated error list is immutable"() {
        given: 'any invalid tree'
        write('config.yaml', 'autonomy:\n  attemptLimit: 1\n')
        write('pipeline.yaml', 'stages: []\n')

        when:
        def outcome = PipelineLoader.load(root)
        (outcome as LoadOutcome.Invalid).errors().add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }

    private static Map<String, String> snapshot(Path root) {
        Map<String, String> files = [:]
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                files.put(root.relativize(p).toString(), Files.readString(p))
            }
        }
        files
    }
}
