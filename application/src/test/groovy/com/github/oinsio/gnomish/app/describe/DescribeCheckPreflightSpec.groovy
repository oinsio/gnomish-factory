package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.JudgeChecks

/**
 * The describe preflight's check use site (FR1 of migrate-verify-to-executor-contract): a verify
 * check needs its executor to serve role {@code verdict}, judged against the executor's describe
 * the way a stage's {@code transform} is, and refused as a located law error naming the check, the
 * executor and the role.
 */
class DescribeCheckPreflightSpec extends DescribePreflightSpecBase {

    // Scenario: verification/verdict-executors / Executor without the verdict role is refused
    def "FR1: a check whose executor's describe lists only transform is a located law error naming the check, the executor and the role"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('transform-only')],
        verifying(check(VerifyCheck.Token.EXECUTOR, 'acme'))))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('stages/build/stage.yaml', 'verify[0].executor',
            "executor 'acme' does not serve role 'verdict', which check 'sonar/gate' of stage 'build' needs; its describe lists transform")
        ])
    }

    // FR1: the external form names its executor by provider, so the error points there; checks keep
    // their order, and a judge or a check naming an executor no law declares is not judged here
    def "FR1: the external form is located at its provider, and only described executors are judged"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('transform-only')],
        verifying(
                check(VerifyCheck.Token.EXECUTOR, 'github-actions'),
                JudgeChecks.agent('criteria.md', 'model'),
                check(VerifyCheck.Token.EXTERNAL, 'acme'))))

        then:
        outcome instanceof DescribeOutcome.Invalid
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == ['verify[2].provider']
    }

    // FR1: an executor serving verdict passes the check use site
    def "FR1: a check whose executor serves verdict passes the preflight"() {
        expect:
        preflight().describe(environment, law(['acme': describeAs('verdict-only')],
        verifying(check(VerifyCheck.Token.EXECUTOR, 'acme')))) instanceof DescribeOutcome.Described
    }

    // FR1: a stage's own executor is judged before its checks, in manifest order
    def "FR1: a stage naming a verdict-only executor and checking with a transform-only one reports the stage first"() {
        given:
        def stage = new StageDefinition('build', 'Purpose of build', [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(ExecutorType.PROGRAM, '', [:], Sandbox.none(), 'judge'),
        'stages/build/instructions.md', [
            check(VerifyCheck.Token.EXECUTOR, 'acme')
        ], new AutonomyLimits(2),
        AdvancementMode.AUTO)

        when:
        def outcome = preflight().describe(environment, law([
            'acme': describeAs('transform-only'),
            'judge': describeAs('verdict-only')
        ], stage))

        then:
        outcome instanceof DescribeOutcome.Invalid
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [
            'executor.name',
            'verify[0].executor'
        ]
    }

    // Scenario: pipeline/pipeline-config / Explicit executor form
    def "FR1: a check naming a law-declared executor passes when its settings fit that executor's schema, and is a located error when they break it"() {
        given:
        def program = describeAs('strict-verdict')
        def law = { Map settings ->
            law(['acme': program], verifying(withSettings(check(VerifyCheck.Token.EXECUTOR, 'acme'), settings)))
        }

        expect: 'settings the schema accepts pass the preflight'
        preflight().describe(environment, law([maxTurns: 3])) instanceof DescribeOutcome.Described

        and: 'settings the schema refuses are a located error at the check\'s settings path'
        preflight().describe(environment, law([maxTurn: 3])) == new DescribeOutcome.Invalid([
            new ConfigError('stages/build/stage.yaml', 'verify[0].settings.maxTurn',
            "executor 'acme' settings break its schema's rule 'additionalProperties': "
            + "property 'maxTurn' is not defined in the schema and the schema does not allow additional properties")
        ])
    }

    // FR1: the external form carries its settings under params, so the error points there
    def "FR1: an external check's settings breaking its executor's schema are located under params"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('strict-verdict')], verifying(
                withSettings(check(VerifyCheck.Token.EXTERNAL, 'acme'), [maxTurns: 0]))))

        then:
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == ['verify[0].params.maxTurns']
    }

    // FR1: a schema the factory cannot use is reported once, at the declaration, not again per check
    def "FR1: an unusable settings schema is reported at the declaration only, not at each check naming it"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('remote-schema')],
        verifying(withSettings(check(VerifyCheck.Token.EXECUTOR, 'acme'), [maxTurns: 3]))))

        then:
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [
            'executors.acme.program',
            'verify[0].executor'
        ]
    }

    // FR1, FR4: an answer in another protocol is not held to its settings schema at the check either
    def "FR1: a check naming an executor that speaks another protocol is not judged against its schema"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('protocol-2')],
        verifying(withSettings(check(VerifyCheck.Token.EXECUTOR, 'acme'), [anything: 1]))))

        then:
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [
            'executors.acme',
            'verify[0].executor'
        ]
    }

    private static VerifyCheck.Executor withSettings(VerifyCheck.Executor check, Map<String, Object> settings) {
        new VerifyCheck.Executor(check.token(), check.executorName(), check.checkId(), settings, check.interval(),
                check.timeout(), check.timeoutClass(), check.pinPaths(), check.verifyIn(), check.threshold(), check.expected())
    }
}
