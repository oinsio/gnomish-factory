package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * External-check provider resolution at the composition point (FR6, FR13, M4 of
 * add-plugin-architecture). A check that names no provider — the shape every manifest
 * written before providers had — keeps loading and resolves to {@code github},
 * recorded explicitly in the model rather than left implicit. A provider nobody serves
 * is a located load error naming both the requested provider and the discovered set,
 * aggregated in the same single pass as every unrelated problem (UX1) so the operator
 * fixes both from one report.
 *
 * <p>Implements FR6, FR13 (+ UX1, M4) of add-plugin-architecture.
 */
class PipelineLoaderCheckProviderSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    def "an external check declaring no provider resolves to github, recorded in the model"() {
        given:
        writeValidTree()

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        def external = (outcome as LoadOutcome.Loaded).definition().stages()[1].verify()[1] as VerifyCheck.External
        external.provider() == 'github'
        external.params() == [:]
    }

    def "an external check naming an undiscovered provider is a located error aggregated with the rest"() {
        given: 'a valid tree whose build stage selects sonar, and whose plan stage pins no judge model'
        writeValidTree()
        write('stages/build/stage.yaml', buildManifest().replace('checkId: ci\n', 'checkId: ci\n    provider: sonar\n'))
        write('stages/plan/stage.yaml', planManifest().replace('    model: judge-model\n', ''))

        when:
        def outcome = loadTree()

        then: 'one Invalid outcome carries the unrelated core error and the provider error together'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.where() == 'verify[1].model'
        }
        errors.any {
            it.file() == 'stages/build/stage.yaml' && it.where() == 'verify[1].provider' &&
            it.message().contains("'sonar'") && it.message().contains('github')
        }
    }
}
