package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import spock.lang.Specification

/**
 * FR10 of define-executor-contract (design D10, the egress allowlist union): the hosts a law grants
 * its program executors, in the guard's spelling, each once, in declaration order.
 */
class ExecutorEgressSpec extends Specification {

    def "FR10: the union of every executor's granted hosts, normalized and each once"() {
        given:
        def law = new PipelineDefinition('1', new AutonomyLimits(2), [], null, [
            declaration('acme', [
                ' API.Acme.Example ',
                '*.cdn.example'
            ]),
            declaration('lint', [
                'api.acme.example',
                '10.0.0.1'
            ]),
            declaration('quiet', []),
        ])

        expect:
        ExecutorEgress.union(law) == [
            'api.acme.example',
            '*.cdn.example',
            '10.0.0.1'
        ]
    }

    def "FR10: a law declaring no executor grants no host"() {
        expect:
        ExecutorEgress.union(new PipelineDefinition('1', new AutonomyLimits(2), [], null, [])) == []
    }

    private static ExecutorDeclaration declaration(String name, List<String> egress) {
        new ExecutorDeclaration(name, ExecutorDeclaration.Type.PROGRAM, ['/opt/' + name], [:], [], egress)
    }
}
