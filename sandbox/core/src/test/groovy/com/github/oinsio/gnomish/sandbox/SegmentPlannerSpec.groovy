package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.BindingFixtures.*

import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import spock.lang.Specification

/**
 * SegmentPlanner: groups a pipeline's stages into environment segments (design
 * D8, FR12, FR13, NFR-P1 of add-sandbox-core). Contiguous same-binding stages
 * share one segment — the in-segment reuse that avoids repeated clones — while a
 * binding change or a {@code requires-fresh} stage opens the next segment.
 *
 * FR12/FR13/NFR-P1: segment boundaries fall exactly on binding changes and
 * forced-freshness stages; same-binding neighbours reuse one environment.
 *
 * FR9/M2 of open-adapter-binding-registry: the segment-boundary test migrated
 * from a reference {@code !=} on enum constants to config-name identity, and
 * this suite is the behaviour gate for that — every assertion below is the
 * pre-registry one, unchanged.
 */
class SegmentPlannerSpec extends Specification implements StageFixture {

    private static PipelineDefinition pipeline(List<StageDefinition> stages) {
        new PipelineDefinition('1.0.0', new AutonomyLimits(3), stages)
    }

    private static SegmentPlanner planner(String defaultBinding = null, Map<String, String> bindings = [:]) {
        new SegmentPlanner(new BindingResolver(new BindingProperties(defaultBinding, bindings), hostAndContainer()))
    }

    // FR12: a pipeline with no stages produces no segments
    def "an empty pipeline produces no segments"() {
        expect: 'planning a stage-less pipeline yields an empty segment list'
        planner().plan(pipeline([])) == []
    }

    // FR12: a single stage is one segment under its resolved binding
    def "a single stage is one segment under its resolved binding"() {
        when: 'a one-stage pipeline is planned with the container default'
        def segments = planner().plan(pipeline([stage('build')]))

        then: 'there is exactly one container segment holding that stage'
        segments.size() == 1
        segments[0].binding() == containerBinding()
        segments[0].stages()*.name() == ['build']
    }

    // FR12/NFR-P1: contiguous same-binding stages share one segment (in-segment reuse, no re-clone)
    def "contiguous same-binding stages share one segment"() {
        when: 'three same-binding stages are planned'
        def segments = planner().plan(pipeline([
            stage('plan'),
            stage('implement'),
            stage('review')
        ]))

        then: 'they collapse into a single reused-environment segment'
        segments.size() == 1
        segments[0].binding() == containerBinding()
        segments[0].stages()*.name() == ['plan', 'implement', 'review']
    }

    // FR12: a binding change opens a new segment at the boundary
    def "a binding change opens a new segment"() {
        given: 'a pipeline whose middle stage is bound to host, the rest to container'
        def segments = planner(null, [debug: 'host']).plan(
        pipeline([
            stage('plan'),
            stage('debug'),
            stage('review')
        ]))

        expect: 'three segments split on each binding change'
        segments*.binding() == [
            containerBinding(),
            hostBinding(),
            containerBinding()
        ]
        segments.collect { it.stages()*.name() } == [
            ['plan'],
            ['debug'],
            ['review']
        ]
    }

    // FR13: a requires-fresh stage splits the segment even under the same binding
    def "a requires-fresh stage splits the segment under the same binding"() {
        given: 'three same-binding stages where the middle one forces freshness'
        def segments = planner().plan(
                pipeline([
                    stage('plan'),
                    stage('implement', true),
                    stage('review')
                ]))

        expect: 'the fresh stage opens a new segment that the following stage then reuses'
        segments.size() == 2
        segments*.binding() == [
            containerBinding(),
            containerBinding()
        ]
        segments.collect { it.stages()*.name() } == [
            ['plan'],
            ['implement', 'review']
        ]
    }

    // FR13: requires-fresh on the very first stage still yields a single leading segment
    def "requires-fresh on the first stage yields a single leading segment"() {
        when: 'the first stage forces freshness'
        def segments = planner().plan(pipeline([
            stage('plan', true),
            stage('implement')
        ]))

        then: 'the two same-binding stages still share one segment (no prior environment to reset)'
        segments.size() == 1
        segments[0].stages()*.name() == ['plan', 'implement']
    }

    // FR12: every stage lands in exactly one segment, covering the pipeline in order
    def "segments cover every stage exactly once in pipeline order"() {
        given: 'a five-stage pipeline mixing bindings and a fresh stage'
        def segments = planner(null, [debug: 'host']).plan(pipeline([
            stage('plan'),
            stage('implement', true),
            stage('debug'),
            stage('verify'),
            stage('ship')
        ]))

        expect: 'flattening the segment stages reproduces the pipeline order exactly once'
        segments*.stages().flatten()*.name() == [
            'plan',
            'implement',
            'debug',
            'verify',
            'ship'
        ]
    }
}
