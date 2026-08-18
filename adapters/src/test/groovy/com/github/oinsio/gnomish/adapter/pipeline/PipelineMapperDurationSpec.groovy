package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * The external-check timing contract of {@code PipelineMapper} (FR11 of
 * load-pipeline-config, deferred here by task 5.1; FR9 of
 * add-external-check-github-actions): raw {@code interval}/{@code timeout} strings parse
 * to {@code java.time.Duration}, an absent one becomes {@code Duration.ZERO} for
 * StageSanityRule (4.4) to flag, and a malformed one — like an unrecognized
 * {@code timeout-class} — is a located {@code ConfigError} that discards the whole
 * definition, so the loader (task 6.5) aggregates every such error into one list.
 *
 * <p>Implements FR11 of load-pipeline-config; FR9 of add-external-check-github-actions.
 */
class PipelineMapperDurationSpec extends Specification implements PipelineMapperFixtureSupport {

    def "parses external timing string #interval / #timeout to Durations"() {
        given:
        def stage = apiStage([
            new VerifyCheckDto.External('ci', interval, timeout, null)
        ])

        when:
        def check = mappedStage(stage).verify()[0] as VerifyCheck.External

        then:
        check.interval() == expectedInterval
        check.timeout() == expectedTimeout

        where:
        interval | timeout || expectedInterval | expectedTimeout
        '30s' | '15m' || Duration.ofSeconds(30) | Duration.ofMinutes(15)
        'PT1H' | 'PT2H' || Duration.ofHours(1) | Duration.ofHours(2)
        '500ms' | '1s' || Duration.ofMillis(500) | Duration.ofSeconds(1)
    }

    // A null interval/timeout maps to Duration.ZERO, which StageSanityRule (4.4) flags
    // as non-positive — no crash, no format error
    def "maps a null external #field to zero for the domain rule to flag"() {
        given:
        def stage = apiStage([
            new VerifyCheckDto.External('ci', interval, timeout, null)
        ])

        when:
        def result = mapOne(stage)

        then: 'no format error; the absent field becomes zero (never null)'
        result.errors().isEmpty()
        def check = result.definition().stages()[0].verify()[0] as VerifyCheck.External
        check.interval() != null
        check.timeout() != null
        check.interval() == expectedInterval
        check.timeout() == expectedTimeout

        where:
        field | interval | timeout || expectedInterval | expectedTimeout
        'interval' | null | '15m' || Duration.ZERO | Duration.ofMinutes(15)
        'timeout' | '30s' | null || Duration.ofSeconds(30) | Duration.ZERO
    }

    def "reports a malformed external #field as a located error"() {
        given:
        def stage = apiStage([
            new VerifyCheckDto.External('ci', interval, timeout, null)
        ])

        when:
        def result = PipelineMapper.map(config('1', 1), [entry('build', stage)])

        then: 'exactly one located error, no definition'
        result.definition() == null
        result.errors() == [
            new ConfigError('stages/build/stage.yaml', where, message),
        ]

        where:
        field | interval | timeout || where | message
        'interval' | 'banana' | '15m' || 'verify[0].interval' | "malformed duration 'banana'; use e.g. '30s', '15m', '2h'"
        'timeout' | '30s' | 'nonsense'|| 'verify[0].timeout' | "malformed duration 'nonsense'; use e.g. '30s', '15m', '2h'"
    }

    // Aggregation: malformed durations across stages/checks all collected, located by
    // stage manifest and verify index, in stage then check order
    def "aggregates all malformed durations across stages in order"() {
        given:
        def s0 = new StageDto('p', null, [new ArtifactOutputDto('o0')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.External('a', 'bad1', '1m', null)
        ], null, 'auto')
        def s1 = new StageDto('p', null, [new ArtifactOutputDto('o1')],
        new ExecutorDto('api', 'm', null), 'i.md',
        [
            new VerifyCheckDto.Command('x'),
            new VerifyCheckDto.External('b', '1s', 'bad2', null),
        ] as List<VerifyCheckDto>, null, 'auto')

        when:
        def result = PipelineMapper.map(config('1', 1), [
            entry('plan', s0),
            entry('build', s1)
        ])

        then: 'both errors, located by stage manifest and verify index, in order'
        result.definition() == null
        result.errors() == [
            new ConfigError('stages/plan/stage.yaml', 'verify[0].interval', "malformed duration 'bad1'; use e.g. '30s', '15m', '2h'"),
            new ConfigError('stages/build/stage.yaml', 'verify[1].timeout', "malformed duration 'bad2'; use e.g. '30s', '15m', '2h'"),
        ]
    }

    // FR9: absent timeout-class defaults to quality — unchanged engine behavior
    // (delta-spec scenario "Absent timeout class defaults to quality")
    def "defaults an absent external timeout-class to quality"() {
        given:
        def stage = apiStage([
            new VerifyCheckDto.External('ci', '30s', '15m', null)
        ])

        when:
        def check = mappedStage(stage).verify()[0] as VerifyCheck.External

        then:
        check.timeoutClass() == VerifyCheck.TimeoutClass.QUALITY
    }

    def "loads a declared external timeout-class #raw to #expected"() {
        given:
        def stage = apiStage([
            new VerifyCheckDto.External('ci', '30s', '15m', raw)
        ])

        when:
        def check = mappedStage(stage).verify()[0] as VerifyCheck.External

        then:
        check.timeoutClass() == expected

        where:
        raw || expected
        'quality' || VerifyCheck.TimeoutClass.QUALITY
        'infrastructure' || VerifyCheck.TimeoutClass.INFRASTRUCTURE
    }

    // delta-spec scenario "Unknown timeout class is rejected"
    def "reports an unrecognized external timeout-class as a located error"() {
        given:
        def stage = apiStage([
            new VerifyCheckDto.External('ci', '30s', '15m', 'urgent')
        ])

        when:
        def result = PipelineMapper.map(config('1', 1), [entry('build', stage)])

        then: 'exactly one located error, no definition'
        result.definition() == null
        result.errors() == [
            new ConfigError('stages/build/stage.yaml', 'verify[0].timeout-class',
            "unknown timeout class 'urgent'; use 'quality' or 'infrastructure'"),
        ]
    }
}
