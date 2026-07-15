package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ArtifactInput: the sealed model of a stage input (design D5) — either
 * {@code Internal}, referencing the {@code id} of an output produced by an
 * earlier stage, or {@code Source}, an input with no producing stage. Both are
 * inert data: resolving an internal reference to a strictly-earlier stage is
 * the DAG validator's concern (design D4, task 4.3).
 * Implements FR4 of load-pipeline-config.
 */
class ArtifactInputSpec extends Specification {

    // D5/FR4: exhaustive switching needs the variant set to be closed
    def "ArtifactInput is sealed over exactly Internal and Source"() {
        expect: 'the interface is sealed'
        ArtifactInput.isSealed()

        and: 'its only permitted implementations are the two input kinds'
        ArtifactInput.permittedSubclasses*.name.toSorted() ==
                [
                    ArtifactInput.Internal.name,
                    ArtifactInput.Source.name
                ].toSorted()
    }

    // FR4: an internal input names the producing output by its stable id
    def "Internal exposes the referenced producer output id"() {
        when: 'an internal input is modeled against an output id'
        ArtifactInput input = new ArtifactInput.Internal('build-plan')

        then: 'the variant exposes exactly the referenced id'
        input.producerOutputId() == 'build-plan'
    }

    // D4/task 4.3: reference sanity (dangling, forward, blank) belongs to the
    // DAG validator as a located ConfigError — the record must carry an
    // unresolvable value so the validator can still see and report it
    def "Internal carries an unresolvable reference (#reason) without throwing"() {
        when: 'an internal input is modeled with an id the validators would reject'
        def input = new ArtifactInput.Internal(producerOutputId)

        then: 'the record carries the value untouched for the validator to report'
        notThrown(Exception)
        input.producerOutputId() == producerOutputId

        where:
        producerOutputId | reason
        ''               | 'empty reference'
        '  '             | 'whitespace-only reference'
        'no-such-output' | 'dangling reference — resolution is task 4.3'
    }

    // FR4: a source input declares it has no producing stage — a field-less marker
    def "Source declares no producer and carries no fields"() {
        when: 'a source input is modeled'
        ArtifactInput input = new ArtifactInput.Source()

        then: 'it is a Source and exposes no producer reference'
        input instanceof ArtifactInput.Source
        !(input instanceof ArtifactInput.Internal)
    }

    // FR4: inputs are plain values — validators and specs compare by content
    def "inputs with the same shape are equal values"() {
        expect: 'two independently constructed inputs with equal fields are equal'
        new ArtifactInput.Internal('build-plan') == new ArtifactInput.Internal('build-plan')
        new ArtifactInput.Source() == new ArtifactInput.Source()
    }
}
