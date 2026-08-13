package com.github.oinsio.gnomish.adapter.environment

import spock.lang.Specification

/**
 * Segment: the inert, immutable span a single environment lives for — one binding
 * and the contiguous stages that share it (design D8, FR12 of add-sandbox-core).
 * The stage list is defensively copied, exposed immutable, and never empty.
 *
 * FR12: a segment is typed, immutable data.
 */
class SegmentSpec extends Specification implements StageFixture {

    // FR12: a segment exposes its binding and its stages in order
    def "a segment exposes its binding and its stages in order"() {
        given: 'two stages under the container binding'
        def stages = [
            stage('plan'),
            stage('implement')
        ]

        when: 'a segment is built'
        def segment = new Segment(AdapterBinding.CONTAINER, stages)

        then: 'it exposes the binding and exactly those stages'
        segment.binding() == AdapterBinding.CONTAINER
        segment.stages() == stages
    }

    // FR12: the model is immutable — defensive copy isolates from the source list
    def "a segment is isolated from later mutation of the source stages"() {
        given: 'a mutable source stage list'
        def source = [stage('plan')]

        when: 'the segment is built and the source grows afterwards'
        def segment = new Segment(AdapterBinding.HOST, source)
        source << stage('later-noise')

        then: 'the segment still holds only the original stage'
        segment.stages()*.name() == ['plan']
    }

    // FR12: the exposed stage list itself cannot be mutated
    def "the exposed stage list is immutable"() {
        given: 'a segment with one stage'
        def segment = new Segment(AdapterBinding.CONTAINER, [stage('plan')])

        when: 'a caller tries to add into the exposed list'
        segment.stages() << stage('intruder')

        then: 'the list rejects the mutation'
        thrown(UnsupportedOperationException)
    }

    // FR12: an empty segment is meaningless — an environment with no stage to run
    def "an empty stage list is rejected naming the field"() {
        when: 'a segment is built with no stages'
        new Segment(AdapterBinding.CONTAINER, [])

        then: 'construction fails and the message names Segment.stages'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Segment.stages')
    }

    // FR12: segments are plain values compared by content
    def "segments with the same binding and stages are equal values"() {
        given: 'the same stage in two independently built segments'
        def stage = stage('plan')

        expect: 'the segments are equal'
        new Segment(AdapterBinding.CONTAINER, [stage]) == new Segment(AdapterBinding.CONTAINER, [stage])
    }
}
