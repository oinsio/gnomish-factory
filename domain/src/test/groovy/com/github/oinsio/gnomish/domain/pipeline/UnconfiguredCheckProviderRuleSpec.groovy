package com.github.oinsio.gnomish.domain.pipeline

import java.time.Duration
import spock.lang.Specification

/**
 * UnconfiguredCheckProviderRule: the startup refusal of an `external` check whose provider has no
 * `factory.check.<provider>` section (FR3, design D3 of remove-interactive-console), located and
 * worded so the operator reads which section to write (NFR-O1).
 */
class UnconfiguredCheckProviderRuleSpec extends Specification {

    private static VerifyCheck external(String checkId, String provider) {
        new VerifyCheck.External(checkId, provider, Duration.ofSeconds(30), Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY)
    }

    private static StageDefinition stage(String name, List<VerifyCheck> verify) {
        new StageDefinition(
                name, "Purpose of $name",
                [new ArtifactInput.Source()], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, "$name-model", [:]),
                "stages/$name/instructions.md",
                verify,
                new AutonomyLimits(2), AdvancementMode.AUTO)
    }

    private static String message(String provider, String configured) {
        "check provider '$provider' has no factory.check.$provider section in this factory's configuration; configured providers: $configured"
    }

    def "FR3: #situation"() {
        expect:
        UnconfiguredCheckProviderRule.validate(stages, configured as Set) == expected

        where:
        situation | stages | configured || expected
        'a configured provider yields no error' | [
            stage('build', [external('ci', 'github')])
        ] | ['github'] || []
        'an unconfigured provider is located' | [
            stage('build', [
                new VerifyCheck.Command('true'),
                external('ci', 'github')
            ])
        ] | ['http'] || [
            new ConfigError('stages/build/stage.yaml', 'verify[1].provider', message('github', '[http]'))
        ]
        'no configuration at all refuses every check' | [
            stage('build', [external('ci', 'github')])
        ] | [] || [
            new ConfigError('stages/build/stage.yaml', 'verify[0].provider', message('github', '[]'))
        ]
        'a pipeline with no external check passes' | [
            stage('build', [
                new VerifyCheck.Command('true')
            ])
        ] | [] || []
        'two stages report in pipeline order' | [
            stage('plan', [external('a', 'sonar')]),
            stage('build', [external('b', 'github')])
        ] | ['http'] || [
            new ConfigError('stages/plan/stage.yaml', 'verify[0].provider', message('sonar', '[http]')),
            new ConfigError('stages/build/stage.yaml', 'verify[0].provider', message('github', '[http]'))
        ]
        'the configured set is named sorted' | [
            stage('build', [external('ci', 'github')])
        ] | ['zeta', 'http'] || [
            new ConfigError('stages/build/stage.yaml', 'verify[0].provider', message('github', '[http, zeta]'))
        ]
    }

    def "an empty stage list yields no error"() {
        expect:
        UnconfiguredCheckProviderRule.validate([], [] as Set) == []
    }

    def "the returned error list is immutable"() {
        given:
        def errors = UnconfiguredCheckProviderRule.validate([
            stage('build', [external('ci', 'github')])
        ], [] as Set)

        when:
        errors.add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }
}
