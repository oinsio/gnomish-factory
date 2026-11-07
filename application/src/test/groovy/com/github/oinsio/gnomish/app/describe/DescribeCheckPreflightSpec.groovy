package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration

/**
 * The describe preflight's check use site (FR1 of migrate-verify-to-executor-contract): a verify
 * check needs its executor to serve role {@code verdict}, judged against the executor's describe
 * the way a stage's {@code transform} is, and refused as a located law error naming the check, the
 * executor and the role.
 */
class DescribeCheckPreflightSpec extends DescribePreflightSpecBase {

    private static VerifyCheck.Executor check(VerifyCheck.Token token, String executor) {
        new VerifyCheck.Executor(token, executor, 'sonar/gate', [:], Duration.ofSeconds(5), Duration.ofMinutes(5),
        VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.DEFAULT, ['sonar/gate'])
    }

    private static StageDefinition verifying(VerifyCheck... checks) {
        new StageDefinition('build', 'Purpose of build', [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, '', [:], Sandbox.none(), null),
        'stages/build/instructions.md', checks.toList(), new AutonomyLimits(2), AdvancementMode.AUTO)
    }

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
                new VerifyCheck.Judge('criteria.md', 'model', [:], 1),
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
}
