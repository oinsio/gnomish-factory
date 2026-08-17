package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import java.time.Duration
import spock.lang.Specification

/**
 * The claim-heartbeat keys of the {@code tracker} section as {@code PipelineMapper} maps
 * them (FR3 of add-claim-heartbeat, design D8): omitting both keys resolves to a 5-minute
 * interval and TTL multiplier 3, a declared short-form interval parses to a
 * {@code Duration}, and a malformed one is a located {@code config.yaml} error that
 * discards the definition and aggregates with unrelated stage errors — the same contract
 * external-check durations follow in {@link PipelineMapperDurationSpec}.
 *
 * <p>Implements FR3, D8 of add-claim-heartbeat.
 */
class PipelineMapperHeartbeatSpec extends Specification implements PipelineMapperFixtureSupport {

    // delta-spec scenario "Defaults apply"
    def "defaults the heartbeat interval to 5 minutes and the multiplier to 3 when omitted"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3))

        when:
        def tracker = PipelineMapper.map(cfg, []).definition().tracker()

        then:
        tracker.heartbeatInterval() == Duration.ofMinutes(5)
        tracker.heartbeatTtlMultiplier() == 3
    }

    def "parses a declared heartbeat interval #interval and carries the multiplier through"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, interval, 4, [:]))

        when:
        def tracker = PipelineMapper.map(cfg, []).definition().tracker()

        then:
        tracker.heartbeatInterval() == expected
        tracker.heartbeatTtlMultiplier() == 4

        where:
        interval || expected
        '30s' || Duration.ofSeconds(30)
        '15m' || Duration.ofMinutes(15)
        'PT2H' || Duration.ofHours(2)
    }

    def "reports a malformed heartbeat interval as a located config.yaml error"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, 'banana', 3, [:]))

        when:
        def result = PipelineMapper.map(cfg, [])

        then:
        result.definition() == null
        result.errors() == [
            new ConfigError('config.yaml', 'tracker.heartbeat-interval',
            "malformed duration 'banana'; use e.g. '30s', '15m', '2h'")
        ]
    }

    // delta-spec scenario "Heartbeat errors aggregate": stage errors first, then the tracker error
    def "aggregates a malformed heartbeat interval with an unrelated duration error"() {
        given:
        def stage = ioLessStage([
            new VerifyCheckDto.External('ci', 'bad', '15m', null)
        ])
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, 'oops', 3, [:]))

        when:
        def result = PipelineMapper.map(cfg, [entry('p', stage)])

        then:
        result.definition() == null
        result.errors() == [
            new ConfigError('stages/p/stage.yaml', 'verify[0].interval',
            "malformed duration 'bad'; use e.g. '30s', '15m', '2h'"),
            new ConfigError('config.yaml', 'tracker.heartbeat-interval',
            "malformed duration 'oops'; use e.g. '30s', '15m', '2h'")
        ]
    }
}
