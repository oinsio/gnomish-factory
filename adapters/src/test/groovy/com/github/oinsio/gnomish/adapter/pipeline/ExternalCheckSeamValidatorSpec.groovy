package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.app.CheckParamsValidator
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.ArtifactOutput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ExternalCheckSeamValidator}: the manifest-side check seam (FR6, FR13, UX1 of
 * add-plugin-architecture).
 *
 * <p>The claims: a provider no discovered jar serves is a located error naming both the selection
 * and the discovered set — including the {@code github} the loader defaults to when a manifest
 * names none, so an absent github jar fails visibly instead of silently; a served provider's params
 * are graded by that provider's own validator and its problems come back as located data; and no
 * provider is ever asked about another's params.
 */
class ExternalCheckSeamValidatorSpec extends Specification {

    private static final String MANIFEST = 'stages/build/stage.yaml'

    private static StageDefinition stage(List<VerifyCheck> checks) {
        new StageDefinition('build', 'p', [new ArtifactInput.Source()], [new ArtifactOutput('o')],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]), 'i.md', checks,
        new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static VerifyCheck.External external(String checkId, String provider, Map params = [:]) {
        new VerifyCheck.External(checkId, provider, params, Duration.ofSeconds(30), Duration.ofMinutes(5),
                VerifyCheck.TimeoutClass.QUALITY, [])
    }

    // FR6/UX1: an unknown provider is a load error naming what was selected and what exists.
    def "a check naming an undiscovered provider is a located error naming it and the discovered set"() {
        when:
        def errors = ExternalCheckSeamValidator.validate(
                [
                    stage([external('gate', 'sonar')])
                ], [github: CheckParamsValidator.none()])

        then:
        errors.size() == 1
        errors[0].file() == MANIFEST
        errors[0].where() == 'verify[0].provider'
        errors[0].message().contains("'sonar'")
        errors[0].message().contains('github')
    }

    // FR13/M2: the defaulted github is graded like any other selection, so a classpath without the
    //     github jar reports the same located error rather than failing mid-run.
    def "the defaulted github provider is reported the same way when no jar serves it"() {
        when: 'the loader-defaulted github selection meets an empty discovered set'
        def errors = ExternalCheckSeamValidator.validate([
            stage([
                external('ci/build', 'github')
            ])
        ], [:])

        then:
        errors.size() == 1
        errors[0].where() == 'verify[0].provider'
        errors[0].message().contains("'github'")
        errors[0].message().contains('[]')
    }

    // FR13/M4: a manifest whose external check resolves to a discovered provider passes untouched.
    def "a check selecting a discovered provider yields no error"() {
        expect:
        ExternalCheckSeamValidator.validate(
                [
                    stage([
                        external('ci/build', 'github')
                    ])
                ], [github: CheckParamsValidator.none()]).isEmpty()
    }

    // FR6: the provider owns its params; the seam only locates the delegation.
    def "a served provider grades its own params and its problems come back located"() {
        given: 'a provider rejecting any params it is handed'
        def rejecting = { String file, String where, Map params ->
            [
                new ConfigError(file, where + '.url', "missing required 'url'")
            ]
        } as CheckParamsValidator

        when:
        def errors = ExternalCheckSeamValidator.validate(
                [
                    stage([
                        new VerifyCheck.Command('./gradlew test'),
                        external('gate', 'http', [poll: 'status'])
                    ])
                ],
                [http: rejecting])

        then: 'the error is located at that check position, under its params'
        errors == [
            new ConfigError(MANIFEST, 'verify[1].params.url', "missing required 'url'")
        ]
    }

    // FR6: "a provider with no matching validator selection is never asked to validate another
    //     provider's params" — dispatch is by the check's own discriminator.
    def "no provider is asked about another provider's params"() {
        given:
        def asked = []
        def recording = { String provider -> {
                String file, String where, Map params ->
                asked << provider; []
            } as CheckParamsValidator
        }

        when:
        ExternalCheckSeamValidator.validate(
                [
                    stage([
                        external('ci', 'github'),
                        external('gate', 'http')
                    ])
                ],
                [github: recording('github'), http: recording('http')])

        then: 'each check was graded once, by its own provider'
        asked == ['github', 'http']
    }

    // FR6/NFR-R1: an unknown provider is not also params-graded — nobody can speak for it — and
    //     errors stay in stage-then-check order for a deterministic report.
    def "errors are reported in check order and an unknown provider is not params-graded"() {
        given:
        def rejecting = { String file, String where, Map params ->
            [
                new ConfigError(file, where, 'rejected')
            ]
        } as CheckParamsValidator

        when:
        def errors = ExternalCheckSeamValidator.validate(
                [
                    stage([
                        external('first', 'nobody'),
                        external('second', 'http')
                    ])
                ], [http: rejecting])

        then:
        errors*.where() == [
            'verify[0].provider',
            'verify[1].params'
        ]
    }

    // Restraint: non-external checks carry no provider and are never graded here.
    def "builtin, command and judge checks are ignored"() {
        expect:
        ExternalCheckSeamValidator.validate([
            stage([
                new VerifyCheck.Builtin('files_exist', [:]),
                new VerifyCheck.Command('./gradlew test'),
                new VerifyCheck.Judge('acceptance.md', 'model-x', [:], 1)
            ])
        ], [:]).isEmpty()
    }
}
