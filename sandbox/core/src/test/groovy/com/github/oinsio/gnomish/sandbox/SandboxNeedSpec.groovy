package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * SandboxNeed: the closed vocabulary of stage needs, each mapped to the
 * capability-passport dimension that satisfies it (design D8, FR14 of
 * add-sandbox-core). An unknown token resolves to empty so the reconciler treats
 * it as unmet (fail-closed).
 *
 * FR14: needs are typed and reconciled against a passport, per dimension.
 */
class SandboxNeedSpec extends Specification {

    private static final CapabilityPassport HOST = CapabilityPassport.hostNoIsolation()
    private static final CapabilityPassport CONTAINER = CapabilityPassport.container()

    // FR14: each need is declared with its documented token
    def "each need exposes its declaration token"() {
        expect: 'the tokens match the manifest grammar'
        SandboxNeed.DOCKER_INSIDE.token() == 'docker-inside'
        SandboxNeed.EGRESS_CONTROL.token() == 'egress-control'
        SandboxNeed.TASK_ISOLATION.token() == 'task-isolation'
        SandboxNeed.ISOLATION.token() == 'isolation'
    }

    // FR14: a known token resolves to its need
    def "fromToken resolves #token to its need"() {
        expect: 'the token resolves to the matching constant'
        SandboxNeed.fromToken(token) == Optional.of(need)

        where:
        token || need
        'docker-inside' || SandboxNeed.DOCKER_INSIDE
        'egress-control' || SandboxNeed.EGRESS_CONTROL
        'task-isolation' || SandboxNeed.TASK_ISOLATION
        'isolation' || SandboxNeed.ISOLATION
    }

    // FR14: an unknown token resolves to empty (the reconciler treats it as unmet)
    def "fromToken of an unrecognized token #token is empty"() {
        expect: 'no need matches an out-of-vocabulary token'
        SandboxNeed.fromToken(token) == Optional.empty()

        where:
        token << [
            'gpu',
            'DOCKER-INSIDE',
            '',
            'docker_inside'
        ]
    }

    // FR14: each need is satisfied by exactly the passports whose dimension meets it
    def "#need is satisfied by host=#byHost and container=#byContainer"() {
        expect: 'the need reads exactly its passport dimension'
        need.satisfiedBy(HOST) == byHost
        need.satisfiedBy(CONTAINER) == byContainer

        where:
        need || byHost | byContainer
        SandboxNeed.DOCKER_INSIDE || true | false
        SandboxNeed.EGRESS_CONTROL || false | true
        SandboxNeed.TASK_ISOLATION || false | true
        SandboxNeed.ISOLATION || false | true
    }
}
