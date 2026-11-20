package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ExternalCheckSeamValidator}: the manifest-side check seam (FR6, FR13, UX1 of
 * add-plugin-architecture; task 5.2 of migrate-verify-to-executor-contract).
 *
 * <p>The claims: a check's settings are graded by the params validator its built-in executor owns,
 * keyed by the executor's name, and its problems come back as located data; no validator is ever
 * asked about another executor's settings; and a check whose executor owns no validator is graded by
 * nothing here — an unknown executor is the law's load validation's error, not this seam's.
 */
class ExternalCheckSeamValidatorSpec extends Specification {

    private static final String MANIFEST = 'stages/build/stage.yaml'

    private static StageDefinition stage(List<VerifyCheck> checks) {
        new StageDefinition('build', 'p', [new ArtifactInput.Source()], [new ArtifactOutput('o')],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [: ]), 'i.md', checks,
        new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static VerifyCheck.Executor external(String checkId, String executor, Map params = [: ]) {
        VerifyChecks.external(checkId, executor, params, Duration.ofSeconds(30), Duration.ofMinutes(5),
                VerifyCheck.TimeoutClass.QUALITY, [])
    }

    private static final CheckSettingsValidator REJECTING = { String file, String where, Map params ->
        [
            new ConfigError(file, where, 'rejected')
        ]
    } as CheckSettingsValidator

    // D18 of migrate-verify-to-executor-contract ("The six built-in names have one owner"): an
    //     executor no validator is keyed by — another built-in, a law's program, or a name nobody
    //     declares — is graded by nothing here; ExecutorDeclarations owns the unknown name.
    def "a check whose executor owns no params validator yields no error"() {
        expect:
        ExternalCheckSeamValidator.validate(
                [
                    stage([
                        external('ci/build', 'scm-status'),
                        external('gate', 'sonar'),
                        external('other', 'nobody-declares-this')
                    ])
                ], ['github-actions': REJECTING]).isEmpty()
    }

    // FR6: the built-in owns its settings; the seam only locates the delegation.
    // Scenario: pipeline/pipeline-config / External check provider defaults to github and validates its params
    def "a built-in grades its own check's settings and its problems come back located"() {
        given: 'a validator rejecting any params it is handed'
        def rejecting = { String file, String where, Map params ->
            [
                new ConfigError(file, where + '.url', "missing required 'url'")
            ]
        } as CheckSettingsValidator

        when:
        def errors = ExternalCheckSeamValidator.validate(
                [
                    stage([
                        VerifyChecks.command('./gradlew test'),
                        external('gate', 'http', [poll: 'status'])
                    ])
                ],
                [http: rejecting])

        then: 'the error is located at that check position, under its params'
        errors == [
            new ConfigError(MANIFEST, 'verify[1].params.url', "missing required 'url'")
        ]
    }

    // FR1, UX2 of migrate-verify-to-executor-contract: a check in the explicit executor: form names
    //     its settings `settings`, and is graded under that field.
    def "an explicit executor check is graded under its settings field"() {
        given:
        def check = new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'http', 'gate', [url: 'x'],
        Duration.ofSeconds(30), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY, [],
        VerifyCheck.VerifyIn.SAME_BOX, Threshold.ERROR, [])

        expect:
        ExternalCheckSeamValidator.validate([stage([check])], [http: REJECTING])*.where() == ['verify[0].settings']
    }

    // FR6: "a validator is never asked to validate another executor's settings" — dispatch is by the
    //     check's own executor name.
    def "no validator is asked about another executor's settings"() {
        given:
        def asked = []
        def recording = { String executor ->
            { String file, String where, Map params ->
                asked << executor; []
            } as CheckSettingsValidator
        }

        when:
        ExternalCheckSeamValidator.validate(
                [
                    stage([
                        external('ci', 'github-actions'),
                        external('gate', 'http')
                    ])
                ],
                ['github-actions': recording('github-actions'), http: recording('http')])

        then: 'each check was graded once, by its own executor\'s validator'
        asked == ['github-actions', 'http']
    }

    // NFR-R1: errors stay in stage-then-check order for a deterministic report.
    def "errors are reported in check order"() {
        when:
        def errors = ExternalCheckSeamValidator.validate(
                [
                    stage([
                        external('first', 'github-actions'),
                        external('second', 'http')
                    ])
                ], [http: REJECTING, 'github-actions': REJECTING])

        then:
        errors*.where() == [
            'verify[0].params',
            'verify[1].params'
        ]
    }

    // Restraint: the in-process built-ins own no params validator, and a judge check names no executor.
    def "builtin, command and judge checks are ignored"() {
        expect:
        ExternalCheckSeamValidator.validate([
            stage([
                VerifyChecks.builtin('files_exist', [: ]),
                VerifyChecks.command('./gradlew test'),
                new VerifyCheck.Judge('acceptance.md', 'model-x', [: ], 1)
            ])
        ], [http: REJECTING, 'github-actions': REJECTING]).isEmpty()
    }
}
