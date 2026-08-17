package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The layered short-circuit of the loader's tiers (design D6, FR8 of load-pipeline-config):
 * a tier that depends on a mapped model is skipped only when its own input could not be
 * produced, and a file that will not parse silences its own semantic checks alone — every
 * other file still reports. Conversely, once a model does map, the pure domain tier and the
 * I/O tier both run and contribute their errors to the same one-pass aggregate.
 *
 * <p>Implements FR8 (+ UX1) of load-pipeline-config.
 */
class PipelineLoaderTierIsolationSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    def "domain rules run when a model maps: an empty pipeline and missing version are both reported"() {
        given: 'config.yaml has no schemaVersion and pipeline.yaml declares an empty stage list'
        write('config.yaml', 'autonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages: []\n')

        when: 'the model maps (empty stages qualifies), so the pure domain tier runs (D6)'
        def outcome = loadTree()

        then: 'Invalid, carrying both domain problems in one pass'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.any {
            it.file() == 'config.yaml' && it.message().toLowerCase().contains('version')
        }
        errors.any {
            it.file() == 'pipeline.yaml' && it.message().contains('declares no stages')
        }
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
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()

        and: "plan's malformed YAML is reported once, and its shape checks are NOT run"
        errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.message().contains('malformed YAML')
        }
        !errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.where() == 'executor.type'
        }

        and: "build's structural error is still reported (other files proceed)"
        errors.any {
            it.file() == 'stages/build/stage.yaml' && it.where() == 'executor.type'
        }
    }

    def "a missing referenced file is reported via the I/O tier when the model maps cleanly"() {
        given: 'everything valid except the build instructions file is absent'
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - build\n')
        write('stages/build/stage.yaml', '''\
purpose: build
executor:
  type: agent-cli
  model: m
instructions: stages/build/instructions.md
advancement: auto
''')

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'stages/build/stage.yaml' && it.where() == 'instructions' &&
            it.message().contains('does not exist')
        }
    }
}
