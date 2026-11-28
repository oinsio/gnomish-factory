package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.JudgeChecks

/**
 * The describe preflight's judge use site (design D12 of extract-agent-round-runner): a {@code
 * judge} check's {@code program} block names the law's executor that judges, which must serve role
 * {@code verdict}, and the block's settings are held to that executor's schema exactly as a stage
 * block naming it is — located under the check's {@code verify[<i>].executor}.
 *
 * <p>FR13, UX1 of extract-agent-round-runner.
 */
class DescribeJudgePreflightSpec extends DescribePreflightSpecBase {

    // Scenario: pipeline/pipeline-config / A judge executor without the verdict role is rejected
    def "FR13, UX1: a judge block naming a program whose describe lists no verdict is a located error naming the check, the executor and the role"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('transform-only')],
        verifying(JudgeChecks.program('criteria.md', 'acme'))))

        then:
        outcome == new DescribeOutcome.Invalid([
            new ConfigError('stages/build/stage.yaml', 'verify[0].executor.name',
            "executor 'acme' does not serve role 'verdict', which judge check 'criteria.md' of stage 'build' needs; its describe lists transform")
        ])
    }

    // FR13: a program serving verdict passes; an agent-cli judge, and a judge naming no described
    //     executor, are not this use site's to judge
    def "FR13: #label passes the judge use site"() {
        expect:
        preflight().describe(environment, law(['acme': describeAs('verdict-only')], verifying(check)))
        instanceof DescribeOutcome.Described

        where:
        label | check
        'a program judge whose executor serves verdict' | JudgeChecks.program('criteria.md', 'acme')
        'an agent-cli judge' | JudgeChecks.agent('criteria.md', 'model')
        'a judge naming an undescribed executor' | JudgeChecks.program('criteria.md', 'other')
    }

    // FR13, UX1: the judge block's settings and model are held to the executor's schema, located at
    //     the block, as a stage block's are
    def "FR13, UX1: a judge block's #part breaking its executor's schema is located at the block"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('strict-verdict')], verifying(check)))

        then:
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [where]

        where:
        part | check || where
        'settings' | JudgeChecks.program('criteria.md', 'acme', [maxTurn: 3]) || 'verify[0].executor.settings.maxTurn'
        'model' | modelled('criteria.md', 'acme', 'm') || 'verify[0].executor.model'
    }

    // FR13: an answer in another protocol is not held to its settings schema at the judge either
    def "FR13: a judge naming an executor that speaks another protocol is not judged against its schema"() {
        when:
        def outcome = preflight().describe(environment, law(['acme': describeAs('protocol-2')],
        verifying(JudgeChecks.program('criteria.md', 'acme', [anything: 1]))))

        then: 'the protocol at the declaration, the role at the judge, and no settings error'
        ((DescribeOutcome.Invalid) outcome).errors()*.where() == [
            'executors.acme',
            'verify[0].executor.name'
        ]
    }

    private static VerifyCheck.Judge modelled(String criteriaFile, String name, String model) {
        new VerifyCheck.Judge(criteriaFile, new StageDefinition.Executor(ExecutorType.PROGRAM, model, [:], Sandbox.none(), name))
    }
}
