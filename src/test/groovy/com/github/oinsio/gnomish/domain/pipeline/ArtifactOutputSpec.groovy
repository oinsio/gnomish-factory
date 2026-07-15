package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * ArtifactOutput: a stage output declaring its stable {@code id} — the handle
 * later stages' internal inputs reference (FR4). The record is inert data:
 * pipeline-wide id uniqueness is the DAG validator's concern (design D4,
 * task 4.3), never a constructor rule.
 * Implements FR4 of load-pipeline-config.
 */
class ArtifactOutputSpec extends Specification {

    // FR4: an output is addressed by its stable id
    def "an output exposes its stable id"() {
        when: 'an output is modeled with an id'
        def output = new ArtifactOutput('build-plan')

        then: 'the id is exposed exactly as declared'
        output.id() == 'build-plan'
    }

    // D6/task 4.3: id sanity (presence, uniqueness) belongs to the validators
    // as a located ConfigError — the record must carry an invalid value so the
    // validator can still see and report it
    def "an output carries a blank id (#reason) without throwing"() {
        when: 'an output is modeled with an id the validators would reject'
        def output = new ArtifactOutput(id)

        then: 'the record carries the value untouched for the validator to report'
        notThrown(Exception)
        output.id() == id

        where:
        id     | reason
        ''     | 'empty id'
        '   '  | 'whitespace-only id'
    }

    // FR4: outputs are plain values — the DAG validator compares ids by content
    def "outputs with the same id are equal values"() {
        expect: 'two independently constructed outputs with equal ids are equal'
        new ArtifactOutput('build-plan') == new ArtifactOutput('build-plan')
    }
}
