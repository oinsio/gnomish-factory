package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.Threshold
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

    // Scenario: pipeline/pipeline-config / External check provider defaults to github and validates its params
    // Scenario: pipeline/pipeline-config / Default external provider maps
    def "an external check declaring no provider resolves to the github-actions executor, recorded in the model"() {
        given:
        writeValidTree()

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        def external = (outcome as LoadOutcome.Loaded).definition().stages()[1].verify()[1] as VerifyCheck.Executor
        external.token() == VerifyCheck.Token.EXTERNAL
        external.executorName() == 'github-actions'
        external.settings() == [:]
    }

    // Scenario: pipeline/pipeline-config / Unknown provider is a located load error
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

    // FR1, UX2 of migrate-verify-to-executor-contract: a manifest written against the check SPI
    // — sugar tokens only, the legacy `provider: github` — loads as it did, its checks naming the
    // executors the tokens stand for
    // Scenario: verification/verdict-executors / Legacy manifest loads unchanged
    def "a legacy manifest with command, builtin and provider-github external checks loads unchanged"() {
        given:
        writeValidTree()
        write('stages/build/stage.yaml', buildManifest().replace('checkId: ci\n', 'checkId: ci\n    provider: github\n'))

        when:
        def outcome = loadTree()

        then: 'no load error'
        outcome instanceof LoadOutcome.Loaded

        and: 'every check names the executor its token maps to, keeping the token'
        def stages = (outcome as LoadOutcome.Loaded).definition().stages()
        def plan = stages[0].verify()[0] as VerifyCheck.Executor
        [
            plan.token(),
            plan.executorName(),
            plan.checkId()
        ] == [
            VerifyCheck.Token.COMMAND,
            'shell',
            'echo ok'
        ]
        def build = stages[1].verify().collect { it as VerifyCheck.Executor }
        build.collect { [it.token(), it.executorName()] } == [
            [
                VerifyCheck.Token.BUILTIN,
                'files-exist'
            ],
            [
                VerifyCheck.Token.EXTERNAL,
                'github-actions'
            ]
        ]
    }

    // FR1 of migrate-verify-to-executor-contract: the explicit `executor:` form is accepted beside
    // the tokens and carries its name, check id, settings and knobs into the model. Its role is
    // judged by the describe preflight (DescribeCheckPreflightSpec); the name against the executor
    // registry once checks resolve through it (tasks 3.6, 5.1).
    def "an explicit executor form loads into an executor check carrying its name, settings and knobs"() {
        given:
        writeValidTree()
        write('stages/build/stage.yaml', buildManifest().replace("""  - type: external
    checkId: ci
    interval: 5s
    timeout: 60s
""", """  - executor: github-actions
    checkId: ci
    settings:
      workflow: ci.yml
    interval: 5s
    timeout: 60s
    threshold: warning
    expected:
      - ci/build
"""))

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        def check = (outcome as LoadOutcome.Loaded).definition().stages()[1].verify()[1] as VerifyCheck.Executor
        check.token() == VerifyCheck.Token.EXECUTOR
        check.executorName() == 'github-actions'
        check.checkId() == 'ci'
        check.settings() == [workflow: 'ci.yml']
        check.threshold() == Threshold.WARNING
        check.expected() == ['ci/build']
    }

    // NFR-S1, FR4 of migrate-verify-to-executor-contract: the loader runs the verdict-check rules —
    // a credentialed executor declared same-box and an scm-status check with no expected contexts
    // are each a located error, in the one pass
    def "a same-box check on a credentialed executor and an scm-status check without expected are located load errors"() {
        given:
        writeValidTree()
        write('config.yaml', """schemaVersion: "1"
autonomy:
  attemptLimit: 3
executors:
  sonarqube:
    type: program
    program: [./sonar]
    credentials: [SONAR_TOKEN]
""")
        write('stages/build/stage.yaml', buildManifest().replace("""  - type: external
    checkId: ci
""", """  - executor: sonarqube
    checkId: gate
    verifyIn: same-box
    interval: 5s
    timeout: 60s
  - executor: scm-status
    checkId: ci
"""))

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors().findAll {
            it.file() == 'stages/build/stage.yaml'
        }
        errors.any {
            it.where() == 'verify[1].verifyIn' && it.message().contains('SONAR_TOKEN')
        }
        errors.any {
            it.where() == 'verify[2].expected' && it.message().contains("'ci'")
        }
    }
}
