package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import java.time.Duration
import spock.lang.Specification

/**
 * Task 5.2 of migrate-verify-to-executor-contract (FR4, FR16, FR17 of add-plugin-architecture;
 * NFR-S1): {@link BuiltInCheckSubsections} is the one owner of the two duties the check-provider seam
 * carried — grading the operator's {@code factory.check.<built-in>} subsections at startup, and the
 * built-ins' credential names the run's scrub and never-allowlist sets take, the credential an {@code
 * http} check names in its own settings among them.
 */
class BuiltInCheckSubsectionsSpec extends Specification {

    private static final String FILE = 'application.yaml'
    private static final Map ACTIONS = [('api-url'): 'https://api.github.com', repo: 'acme/widgets']

    private static BuiltInCheckSubsections subsections(Map configured, Map profiles = [:]) {
        new BuiltInCheckSubsections(configured, ConnectionProfiles.of(profiles))
    }

    private static PipelineDefinition law(List<VerifyCheck> checks) {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('verify', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', checks, new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private static VerifyCheck.Executor http(Map settings) {
        VerifyChecks.external('gate', 'http', settings, Duration.ofSeconds(1), Duration.ofSeconds(30),
                VerifyCheck.TimeoutClass.QUALITY, [])
    }

    def "FR4: subsections every built-in accepts grade clean: #configured"() {
        expect:
        subsections(configured).validate() == []

        where:
        configured << [
            [:],
            [('github-actions'): ACTIONS],
            [http: [:]],
            [('scm-status'): [anything: 'the reader grades it at run assembly']]
        ]
    }

    def "FR4: a key no built-in reads is a located error naming the built-ins configured here, in key order"() {
        when:
        def errors = subsections([zeta: [:], github: ACTIONS]).validate()

        then:
        errors == [
            new ConfigError(FILE, 'factory.check.github',
            'no built-in reads factory.check.github; the built-ins configured here: [github-actions, http, scm-status]'),
            new ConfigError(FILE, 'factory.check.zeta',
            'no built-in reads factory.check.zeta; the built-ins configured here: [github-actions, http, scm-status]')
        ]
    }

    def "FR4: each built-in's own validator grades its subsection, located under its key"() {
        when:
        def errors = subsections([('github-actions'): [repo: 'acme/widgets'], http: [allow: 'x']]).validate()

        then:
        errors*.where() == [
            'factory.check.github-actions.api-url',
            'factory.check.http.allow'
        ]
        errors.every { it.file() == FILE }
    }

    def "FR16: a connection reference is graded against the operator's profiles, and its data by the built-in"() {
        expect: 'an undefined profile is a located error at the reference'
        subsections([('scm-status'): [connection: 'nope']]).validate()*.where() == [
            'factory.check.scm-status.connection'
        ]

        and: 'a defined one resolves before the built-in grades it'
        subsections([('github-actions'): [connection: 'gh', repo: 'acme/widgets']],
        [gh: [('api-url'): 'https://api.github.com']]).validate() == []
    }

    def "FR4: a malformed subsection stops startup naming every problem, and a valid one passes"() {
        when:
        subsections([github: [:], ('github-actions'): [repo: 'acme/widgets']]).requireValid()

        then:
        def e = thrown(IllegalStateException)
        e.message.startsWith('invalid check configuration:')
        e.message.contains('factory.check.github;')
        e.message.contains('factory.check.github-actions.api-url')

        when:
        subsections([('github-actions'): ACTIONS]).requireValid()

        then:
        noExceptionThrown()
    }

    // NFR-S1: the token name of each SCM built-in the operator connected — its describe credential
    //     by default, the name its subsection or profile renames it to — and nothing for one it did not.
    def "FR17: the installation's credential names are the connected SCM built-ins' token names: #why"() {
        expect:
        subsections(configured, profiles).credentialNames() == expected

        where:
        why | configured | profiles || expected
        'none connected' | [http: [:]] | [:] || []
        'the defaults' | [('github-actions'): ACTIONS, ('scm-status'): [:]] | [:] || [
            'GNOMISH_GITHUB_ACTIONS_TOKEN',
            'GNOMISH_SCM_STATUS_TOKEN'
        ]
        'renamed inline' | [('scm-status'): [credential: 'GITEA_TOKEN']] | [:] || ['GITEA_TOKEN']
        'renamed by a profile' | [('github-actions'): [connection: 'gh']] | [gh: [credential: 'GH_TOKEN']] || ['GH_TOKEN']
        'one name for both' | [('github-actions'): [credential: 'T'], ('scm-status'): [credential: 'T']] | [:] || ['T']
    }

    // FR11, FR17: a run adds the token name of each SCM built-in its checks name, connected or not,
    //     and the credential each http check names in its own settings, which its describe cannot
    //     declare — once each; a check of an in-process built-in adds nothing.
    // Scenario: verification/http-check-provider / Secret is injected at request time, never stored in the manifest
    def "FR17: a run's credential names add the credentials its checks name"() {
        given:
        def definition = law([
            http([url: 'https://ci.example.invalid/a', auth: [credential: 'GNOMISH_SONAR_TOKEN']]),
            http([url: 'https://ci.example.invalid/b']),
            http([url: 'https://ci.example.invalid/c', auth: [credential: 'GNOMISH_SONAR_TOKEN']]),
            VerifyChecks.builtin('files', [files: ['a']]),
            VerifyChecks.external('ci.yml', 'github-actions', Duration.ofSeconds(1), Duration.ofSeconds(5),
            VerifyCheck.TimeoutClass.QUALITY)
        ])

        expect:
        subsections([('scm-status'): [credential: 'GITEA_TOKEN']]).credentialNames(definition) == [
            'GITEA_TOKEN',
            'GNOMISH_SONAR_TOKEN',
            'GNOMISH_GITHUB_ACTIONS_TOKEN'
        ]
        subsections([:]).credentialNames(law([])) == []
    }
}
