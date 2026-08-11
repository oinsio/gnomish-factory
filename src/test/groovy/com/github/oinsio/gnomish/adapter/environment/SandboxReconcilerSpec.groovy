package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import spock.lang.Specification

/**
 * SandboxReconciler: reconciles a stage's declared needs against the bound
 * adapter's passport, fail-closed (design D8, FR14 of add-sandbox-core). The
 * returned unmet-need tokens name exactly what refuses the stage, in declaration
 * order; an empty result means the stage may start.
 *
 * FR14: needs unsatisfied by the passport — including unrecognized ones — are
 * reported by token, fail-closed.
 */
class SandboxReconcilerSpec extends Specification {

    private static final CapabilityPassport HOST = CapabilityPassport.hostNoIsolation()
    private static final CapabilityPassport CONTAINER = CapabilityPassport.container()

    private final SandboxReconciler reconciler = new SandboxReconciler()

    // FR14: a stage with no needs is satisfied by any passport
    def "no needs are always satisfied"() {
        expect: 'an empty needs list yields no unmet needs against either passport'
        reconciler.unmetNeeds(Sandbox.none(), HOST) == []
        reconciler.unmetNeeds(Sandbox.none(), CONTAINER) == []
    }

    // FR14: a need the passport satisfies produces no unmet entry
    def "a met need produces no unmet entry"() {
        expect: 'docker-inside is met by host, egress-control by container'
        reconciler.unmetNeeds(new Sandbox(['docker-inside'], false), HOST) == []
        reconciler.unmetNeeds(new Sandbox(['egress-control'], false), CONTAINER) == []
    }

    // FR14: a need the passport does not satisfy is reported by token, fail-closed
    def "an unmet need is reported by token"() {
        expect: 'docker-inside is unmet on the container (no docker-inside support)'
        reconciler.unmetNeeds(new Sandbox(['docker-inside'], false), CONTAINER) == ['docker-inside']

        and: 'isolation is unmet on the host (no isolation boundary)'
        reconciler.unmetNeeds(new Sandbox(['isolation'], false), HOST) == ['isolation']
    }

    // FR14: an unrecognized need is always unmet — an unknown requirement cannot be proven met
    def "an unrecognized need is always unmet"() {
        expect: 'a gpu need is unmet against either passport'
        reconciler.unmetNeeds(new Sandbox(['gpu'], false), HOST) == ['gpu']
        reconciler.unmetNeeds(new Sandbox(['gpu'], false), CONTAINER) == ['gpu']
    }

    // FR14: only the unmet needs are reported, in declaration order, mixed met/unmet/unknown
    def "only the unmet needs are reported, in declaration order"() {
        given: 'a mix of a met need, an unmet need, and an unknown need on the container'
        def sandbox = new Sandbox([
            'egress-control',
            'docker-inside',
            'gpu'
        ], false)

        expect: 'the met egress-control drops out; the unmet and unknown survive in order'
        reconciler.unmetNeeds(sandbox, CONTAINER) == ['docker-inside', 'gpu']
    }
}
