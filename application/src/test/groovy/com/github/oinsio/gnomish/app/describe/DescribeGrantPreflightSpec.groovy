package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.engine.ExecutorCredentialNames
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues

/**
 * The describe preflight holding a law's credential and egress grants to the executor's describe
 * (FR4, NFR-S1 of define-executor-contract; design D5 phase b, D10): each disagreement a located
 * error before the first round, and the granted names, taken from the describe, handed on for the
 * program child's own resolved layer.
 */
class DescribeGrantPreflightSpec extends DescribePreflightSpecBase {

    static final ChildEnvValues SECRETS = { String name ->
        Optional.ofNullable([ACME_API_KEY: 'acme-secret', OTHER_KEY: 'other', GNOMISH_GITHUB_TOKEN: 'gh', OPERATOR_VAR: 'op'][name])
    } as ChildEnvValues

    static final ChildEnvAllowlist ALLOWLIST = ChildEnvAllowlist.of(['OPERATOR_VAR'], ['GNOMISH_GITHUB_TOKEN'], SECRETS)

    def "NFR-S1: a law granting what the describe declares is described, with the granted names handed on"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment,
                grants(describeAs('grants'), ['ACME_API_KEY'], ['api.acme.example']))

        then:
        outcome instanceof DescribeOutcome.Described
        ((DescribeOutcome.Described) outcome).executors().credentials() ==
                new ExecutorCredentialNames([acme: ['ACME_API_KEY']])
    }

    // Scenario: pipeline/pipeline-config / Missing credential grant
    def "FR4, NFR-S1: a credential the describe declares and the law does not grant is a located error naming the executor and the variable"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment, grants(describeAs('grants'), [], ['api.acme.example']))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('config.yaml', 'executors.acme.credentials',
            "executor 'acme' declares credential 'ACME_API_KEY' in its describe, which executors.acme.credentials does not grant")
        ])
    }

    // Scenario: executor/result-channel / Undeclared credential is refused
    def "FR4, NFR-S1: a credential the law grants and the describe does not declare is a located error naming the executor and the variable"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment,
                grants(describeAs('grants'), ['ACME_API_KEY', 'OTHER_KEY'], ['api.acme.example']))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('config.yaml', 'executors.acme.credentials[1]',
            "executor 'acme' is granted credential 'OTHER_KEY', which its describe does not declare; its describe declares ACME_API_KEY")
        ])
    }

    def "NFR-S1: a granted credential that #what is a located error, since it cannot reach this executor alone"() {
        given: 'a describe declaring the name, so only the standing is wrong'
        def program = describeAs('grants')

        when:
        def outcome = preflight(allowlist).describe(environment, grants(program, ['ACME_API_KEY'], ['api.acme.example']))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('config.yaml', 'executors.acme.credentials[0]',
            "executor 'acme' is granted credential 'ACME_API_KEY', ${why}; a credential reaches only its executor".toString())
        ])

        where:
        what | allowlist || why
        'the operator passes through' | ChildEnvAllowlist.of(['ACME_API_KEY'], [], SECRETS) || 'which factory.sandbox.env-passthrough passes to every child'
        'the factory withholds' | ChildEnvAllowlist.of([], ['ACME_API_KEY'], SECRETS) || 'which the factory declares as its own credential and withholds from every child'
        'has no value' | ChildEnvAllowlist.of([], [], ChildEnvValues.NONE) || "which has no value in the factory's secrets"
    }

    def "FR4: an egress host the law grants and the describe does not declare is a located error; hosts compare in the guard's spelling"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment,
                grants(describeAs('grants'), ['ACME_API_KEY'], [
                    ' Api.ACME.example ',
                    '*.acme.example'
                ]))

        then: 'the case-folded host agrees, the wildcard is no host the describe declares'
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('config.yaml', 'executors.acme.egress[1]',
            "executor 'acme' is granted egress host '*.acme.example', which its describe does not declare; its describe declares API.Acme.Example")
        ])
    }

    def "FR4: an egress host the law grants is refused when the describe declares none"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment, grants(describeAs('reference'), [], ['api.acme.example']))

        then:
        only(outcome).message() ==
                "executor 'acme' is granted egress host 'api.acme.example', which its describe does not declare; its describe declares no egress host"
    }

    def "FR4: a granted credential the describe does not list reads 'no credential' when it lists none"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment, grants(describeAs('reference'), ['ACME_API_KEY'], []))

        then:
        only(outcome).message() ==
                "executor 'acme' is granted credential 'ACME_API_KEY', which its describe does not declare; its describe declares no credential"
    }

    def "FR4, NFR-S2: a describe egress host outside the host grammar is the program's configuration error"() {
        when:
        def outcome = preflight(ALLOWLIST).describe(environment, grants(describeAs('bad-egress'), [], []))

        then:
        def error = only(outcome)
        error.where() == 'executors.acme.program'
        error.message().startsWith("executor 'acme' cannot be described (configuration error): its describe declares an "
                + "egress host that is not a host name, an IP literal or a *.suffix wildcard (https://api.acme.example/v1)")
    }

    private static PipelineDefinition grants(java.nio.file.Path program, List<String> credentials, List<String> egress) {
        new PipelineDefinition('1', new AutonomyLimits(2), [stage('build', 'acme')], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, [program.toString()], [:], credentials, egress)
        ])
    }
}
