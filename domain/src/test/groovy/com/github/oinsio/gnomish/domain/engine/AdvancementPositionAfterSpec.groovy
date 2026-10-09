package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import spock.lang.Specification

/**
 * {@code Advancement.positionAfter} — the single owner of what a pass leaves as position (design
 * D7 of make-checkpoint-gate-durable) — over both advancement modes on a middle and a last stage,
 * and its AUTO branch {@code Advancement.afterGate}, the position an approved gate moves to. The
 * engine-driven advancement scenarios live in {@code AdvancementSpec}.
 *
 * <p>Implements FR1, FR3 of make-checkpoint-gate-durable.
 */
class AdvancementPositionAfterSpec extends Specification {

    // FR1 of make-checkpoint-gate-durable: positionAfter is mode-aware — a MANUAL pass leaves the
    //      task at its gate, an AUTO pass at the next stage or the pipeline end — on a middle and
    //      a last stage; afterGate is the AUTO branch whatever the stage's own mode
    def "positionAfter holds a manual pass at its gate and moves an auto pass on"() {
        given: 'a three-stage pipeline whose stage under test has the given mode'
        def stages = ['build', 'test', 'ship'].collect {
            AdvancementSpec.stage(it, it == stageName ? mode : AdvancementMode.AUTO, [])
        }
        def definition = AdvancementSpec.pipeline(*stages)
        def passed = stages.find { it.name() == stageName }

        expect: 'the pass leaves the position the mode decides'
        Advancement.positionAfter(definition, passed) == expected

        and: 'the position after the gate is the AUTO answer, never a gate'
        Advancement.afterGate(definition, passed) == following

        where:
        mode | stageName | expected | following
        AdvancementMode.AUTO | 'test' | new Position.AtStage('ship') | new Position.AtStage('ship')
        AdvancementMode.AUTO | 'ship' | new Position.PipelineEnd() | new Position.PipelineEnd()
        AdvancementMode.MANUAL | 'test' | new Position.AwaitingApproval('test') | new Position.AtStage('ship')
        AdvancementMode.MANUAL | 'ship' | new Position.AwaitingApproval('ship') | new Position.PipelineEnd()
    }
}
