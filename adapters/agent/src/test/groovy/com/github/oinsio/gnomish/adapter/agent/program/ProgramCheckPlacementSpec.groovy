package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import java.time.Duration
import spock.lang.Specification

/**
 * Where a {@code program} verdict check binds in this change (FR1, FR3 of
 * migrate-verify-to-executor-contract; design D18, "A {@code program} check outside a fresh box";
 * proposal NG14): only a {@code fresh-box} check in a run with fresh boxes. Every other check naming
 * one of the law's programs is one located error naming the check and the executor; a built-in's
 * check is never this owner's.
 */
class ProgramCheckPlacementSpec extends Specification {

    static VerifyCheck.Executor program(String id, VerifyCheck.VerifyIn verifyIn) {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'acme', id, [:], Duration.ofSeconds(30),
        Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY, [], verifyIn, Threshold.ERROR, [])
    }

    static PipelineDefinition law(List<VerifyCheck> checks) {
        def stage = new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'i.md', checks, new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(1), [stage], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, ['/opt/acme'], [:], [], [])
        ])
    }

    def "FR3: a fresh-box program check in a run with fresh boxes binds, and so does every built-in's check"() {
        expect:
        ProgramCheckPlacement.refusals(law([
            program('lint', VerifyCheck.VerifyIn.FRESH_BOX),
            VerifyChecks.command('true')
        ]), true).isEmpty()
    }

    def "FR1, FR3: #medium refuses the program check at its manifest and verify index, naming the check and the executor"() {
        when:
        def errors = ProgramCheckPlacement.refusals(law([
            VerifyChecks.command('true'),
            program('lint', verifyIn)
        ]), boxes)

        then:
        errors == [
            new ConfigError('stages/build/stage.yaml', 'verify[1]',
            "check 'lint' names program executor 'acme', which runs only in a fresh box: ${why}".toString())
        ]

        where:
        medium | boxes | verifyIn | why
        'a host run' | false | VerifyCheck.VerifyIn.FRESH_BOX | 'a host-mode run has no fresh box; run the stage in container mode'
        'a same-box check in a box' | true | VerifyCheck.VerifyIn.SAME_BOX | 'the check verifies same-box; declare verify-in: fresh-box'
    }

    // FR4 of extract-agent-round-runner (design D2): a judge whose block is a program is placed as its
    //     check round is — a fresh box — so a host run refuses it alike; an agent judge is never this
    //     owner's (its exemption: the host environment)
    def "FR4: a program judge #outcome #medium, and an agent judge binds anywhere"() {
        when:
        def errors = ProgramCheckPlacement.refusals(law([
            JudgeChecks.agent('agent.md', 'opus'),
            JudgeChecks.program('review.md', 'acme')
        ]), boxes)

        then:
        errors == expected

        where:
        medium | boxes || outcome | expected
        'in a box' | true || 'binds' | []
        'on the host' | false || 'is refused' | [
            new ConfigError('stages/build/stage.yaml', 'verify[1]',
            "check 'review.md' names program executor 'acme', which runs only in a fresh box: "
            + 'a host-mode run has no fresh box; run the stage in container mode')
        ]
    }
}
