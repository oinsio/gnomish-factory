package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR13, NFR-O2, UX3 of define-executor-contract (design D11; task 6.1): what the describe preflight
 * obtained for a run is every described executor's version; which executor a stage runs is the
 * stage's own {@code executor.name}, so the round writer's pin and the round's named executor come
 * from one derivation.
 */
class DescribedExecutorsSpec extends Specification {

    static final UntrustedText V2 = UntrustedText.subprocess('2')

    static final DescribedExecutors DESCRIBED = new DescribedExecutors(
    new ExecutorVersions(acme: V2, lint: UntrustedText.subprocess('5')), ExecutorCredentialNames.none())

    static StageDefinition stage(ExecutorType type, String name) {
        new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(type, 'm', [:], Sandbox.none(), name),
        'i.md', [], new AutonomyLimits(1), AdvancementMode.AUTO)
    }

    // FR13: a stage's round pins only the executor the stage names
    def "a stage naming a described executor runs exactly that executor at its version"() {
        expect:
        DESCRIBED.ranBy(stage(ExecutorType.PROGRAM, 'acme')) == new ExecutorVersions(acme: V2)
        DESCRIBED.executorOf(stage(ExecutorType.PROGRAM, 'acme')) == new RoundExecutor('acme', V2)
    }

    // FR13: nothing to pin for an undescribed name; the round still carries the law's name
    def "a stage naming an undescribed executor pins nothing and is named unversioned"() {
        expect:
        DESCRIBED.ranBy(stage(ExecutorType.PROGRAM, 'ghost')) == ExecutorVersions.none()
        DESCRIBED.executorOf(stage(ExecutorType.PROGRAM, 'ghost')) == new RoundExecutor('ghost', null)
    }

    // FR13: a stage naming no executor runs no described executor and is named by its type word
    def "a stage naming no executor pins nothing and is named by its type"() {
        expect:
        DESCRIBED.ranBy(stage(ExecutorType.AGENT_CLI, null)) == ExecutorVersions.none()
        DESCRIBED.executorOf(stage(ExecutorType.AGENT_CLI, null)) == new RoundExecutor('agent-cli', null)
    }

    def "none describes nothing"() {
        expect:
        DescribedExecutors.none().all() == ExecutorVersions.none()
        DescribedExecutors.none().ranBy(stage(ExecutorType.PROGRAM, 'acme')) == ExecutorVersions.none()
    }
}
