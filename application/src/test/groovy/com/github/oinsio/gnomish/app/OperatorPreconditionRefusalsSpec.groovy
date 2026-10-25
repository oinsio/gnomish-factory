package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import spock.lang.Specification

/**
 * FR14, NFR-R2 of add-sandbox-base-image (design D22): the one classifier of an operator
 * precondition refusal names each refusal's kind and nothing else.
 */
class OperatorPreconditionRefusalsSpec extends Specification {

    def "names the kind of every operator precondition refusal: #kind"() {
        expect:
        OperatorPreconditionRefusals.kindOf(OperatorPreconditionRefusalSamples.of(kind)) == Optional.of(kind)

        where:
        kind << OperatorPreconditionRefusalSamples.kinds()
    }

    def "no kind a check refuses is the remote, which recovers by itself"() {
        expect:
        !OperatorPreconditionRefusalSamples.kinds().contains(DaemonConditionKind.REMOTE)
        OperatorPreconditionRefusalSamples.kinds().size() == DaemonConditionKind.values().length - 1
    }

    def "a failure that is no operator precondition has no kind: #failure.class.simpleName"() {
        expect:
        OperatorPreconditionRefusals.kindOf(failure).isEmpty()

        where:
        failure << [
            new RuntimeException('boom'),
            new UsageException('bad flag'),
            new IllegalStateException('state'),
            new Error('fatal')
        ]
    }
}
