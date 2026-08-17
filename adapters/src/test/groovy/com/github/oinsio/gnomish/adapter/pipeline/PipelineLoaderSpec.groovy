package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
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
 * <p>This spec covers the happy path and the exception contract (design D3, FR8):
 * validation problems are data, returned as LoadOutcome.Invalid; only a genuine I/O
 * fault — an unreadable required file — escapes as an IOException. A tree with problems
 * in multiple independent, model-independent tiers surfaces all of them in one pass (UX1).
 *
 * <p>The remaining loader behaviours are covered by focused sibling specs sharing
 * {@link PipelineLoaderFixtureSupport}: {@code PipelineLoaderTierIsolationSpec} (layered
 * short-circuit, D6), {@code PipelineLoaderPuritySpec} (NFR-S1/NFR-R1/NFR-C1),
 * {@code PipelineLoaderExecutorSettingsSpec} (FR10/FR11), {@code PipelineLoaderCheckProviderSpec}
 * (FR6/FR13 of add-plugin-architecture) and {@code PipelineLoaderTrackerSectionSpec}
 * (FR17 of add-tracker-port).
 *
 * <p>Implements FR1, FR8 (+ UX1) of load-pipeline-config.
 */
class PipelineLoaderSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    def "a fully valid tree loads into a PipelineDefinition with the expected stages in order"() {
        given:
        writeValidTree()

        when:
        def outcome = loadTree()

        then: 'a Loaded outcome carrying the model in pipeline.yaml order'
        outcome instanceof LoadOutcome.Loaded
        def model = (outcome as LoadOutcome.Loaded).definition()
        model.schemaVersion() == '1'
        model.stages()*.name() == ['plan', 'build']

        and: 'the resolved model reflects the manifests (light check — 7.1 asserts field-by-field)'
        model.stages()[0].advancement() == AdvancementMode.AUTO
        model.stages()[1].advancement() == AdvancementMode.MANUAL
        model.stages()[0].executor().type() == ExecutorType.AGENT_CLI
        model.stages()[1].executor().type() == ExecutorType.AGENT_CLI
        model.stages()[1].limits().attemptLimit() == 3
    }

    def "an unreadable required file is an I/O fault (IOException), not a ConfigError"() {
        given: 'a tree with no config.yaml at all'
        write('pipeline.yaml', 'stages:\n  - plan\n')

        when:
        loadTree()

        then: 'the missing required file surfaces as an exception, never Invalid (FR8/D3)'
        thrown(IOException)
    }

    def "a malformed top-level file surfaces its parse error in the aggregate"() {
        given: 'config.yaml is malformed YAML; pipeline.yaml is well-formed'
        write('config.yaml', 'foo: [unclosed\n')
        write('pipeline.yaml', 'stages:\n  - plan\n')

        when:
        def outcome = loadTree()

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
        def outcome = loadTree()

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
        def outcome = loadTree()

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
        def outcome = loadTree()

        then: 'only the consistency error is present — no domain empty-pipeline error is fabricated'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.any {
            it.file() == 'pipeline.yaml' && it.message().contains("stage 'plan' has no manifest")
        }
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
        def outcome = loadTree()

        then: 'Invalid, carrying every independent problem at once (UX1)'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()

        and: 'structural: unknown executor on plan'
        errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.where() == 'executor.type' &&
            it.message().contains("unknown executor 'bogus'")
        }

        and: 'consistency: ghost has no manifest, orphan is dangling'
        errors.any {
            it.file() == 'pipeline.yaml' && it.message().contains("stage 'ghost' has no manifest")
        }
        errors.any {
            it.file() == 'stages/orphan/stage.yaml' && it.message().contains("dangling stage directory 'orphan'")
        }
    }
}
