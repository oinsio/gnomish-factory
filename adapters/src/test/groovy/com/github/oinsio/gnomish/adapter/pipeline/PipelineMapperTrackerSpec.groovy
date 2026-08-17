package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * The {@code tracker} section of {@code config.yaml} as {@code PipelineMapper} maps it
 * (FR17 and FR9 of add-tracker-port, FR6 of add-factory-serve): an absent section stays
 * a null {@code TrackerConfig}, a present one resolves the abort threshold and the wip
 * limit from their defaults or the declared values, and the ONE subsection matching
 * {@code tracker.type} is passed through for downstream short-ref expansion / adapter
 * construction (task 5.15) — non-matching subsections are seam validation's concern.
 *
 * <p>Implements FR17, FR9 of add-tracker-port; FR6, NFR-S3 of add-factory-serve.
 */
class PipelineMapperTrackerSpec extends Specification {

    // delta-spec scenario "No tracker section" — the loader-unchanged contract
    def "maps an absent tracker section to a null TrackerConfig"() {
        given:
        def cfg = new ConfigDto('1', null, null)

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker() == null
    }

    // delta-spec scenario "Defaulted threshold"
    def "defaults the abort threshold to 3 when the tracker section omits it"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', null))

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker() == new TrackerConfig('github', 3)
    }

    def "carries a declared abort threshold through unchanged"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 7))

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker() == new TrackerConfig('github', 7)
    }

    // FR9 of add-tracker-port (task 5.14)
    def "passes the subsection matching tracker.type through to TrackerConfig"() {
        given:
        def githubSection = ['api-url': 'https://api.github.com', 'repo': 'acme/widgets']
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, [github: githubSection]))

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker().subsection() == githubSection
    }

    def "defaults the subsection to an empty map when no subsections are declared"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3))

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker().subsection() == [:]
    }

    // FR6 of add-factory-serve, delta-spec scenario "Default applies" (design D3)
    def "defaults the wip limit to 10 when the tracker section omits it"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', null))

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker().wipLimit() == 10
    }

    // NFR-S3: this value is read exclusively from the TrackerDto the loader parsed out of the
    // factory's OWN clone's .gnomish/config.yaml — a gnome working a task branch has no way to
    // raise it, since the mapper never consults the task branch or any other source for this key.
    def "carries a declared wip limit through unchanged, sourced only from the factory's own clone"() {
        given:
        def cfg = new ConfigDto('1', null, new TrackerDto('github', 3, null, null, 15, [:]))

        when:
        def definition = PipelineMapper.map(cfg, []).definition()

        then:
        definition.tracker().wipLimit() == 15
    }
}
