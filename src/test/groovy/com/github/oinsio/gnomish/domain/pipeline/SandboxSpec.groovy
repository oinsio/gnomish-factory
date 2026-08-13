package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * Sandbox: the immutable, inert model of a stage's Mechanism sandbox
 * declarations (add-sandbox-core FR12, FR13) — the tighten-only needs and the
 * `requiresFresh` freshness knob. The needs list is order-preserving and
 * defensively copied; no reconciliation happens here (that is task group 3).
 * A repo-declared binding never reaches this record — it is refused at the
 * adapter's structural tier (FR14), so no "requested binding" field exists.
 * FR12, FR13: sandbox declarations are carried as typed, inert data.
 */
class SandboxSpec extends Specification {

    // FR12/FR13: a sandbox exposes its needs (in declaration order) and freshness knob
    def "a sandbox exposes its needs in declaration order and the requiresFresh knob"() {
        when: 'a sandbox is modeled with needs and forced freshness'
        def sandbox = new Sandbox(['docker-inside', 'gpu'], true)

        then: 'the record exposes exactly the needs, in order, and the knob'
        sandbox.needs() == ['docker-inside', 'gpu']
        sandbox.requiresFresh()
    }

    // FR13: the empty declaration is the reuse-segment, same-box default form
    def "none() is the empty declaration with no needs and no forced freshness"() {
        expect: 'no needs and requiresFresh false'
        Sandbox.none().needs() == []
        !Sandbox.none().requiresFresh()
    }

    // FR12: the model is immutable — defensive copy isolates from the source list
    def "a sandbox is isolated from later mutation of the source needs"() {
        given: 'a mutable source needs list'
        def source = ['docker-inside']

        when: 'the sandbox is created and the source list grows afterwards'
        def sandbox = new Sandbox(source, false)
        source << 'later-noise'

        then: 'the sandbox still holds only the original needs'
        sandbox.needs() == ['docker-inside']
    }

    // FR12: the exposed needs list itself cannot be mutated
    def "the exposed needs list is immutable"() {
        given: 'a sandbox with one need'
        def sandbox = new Sandbox(['docker-inside'], false)

        when: 'a caller tries to add into the exposed list'
        sandbox.needs() << 'intruder'

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR12/FR13: sandboxes are plain values — the Mechanism compares by content
    def "sandboxes with the same fields are equal values"() {
        expect: 'two independently constructed sandboxes with equal fields are equal'
        new Sandbox(['docker-inside'], true) == new Sandbox(['docker-inside'], true)
        Sandbox.none() == new Sandbox([], false)
    }
}
