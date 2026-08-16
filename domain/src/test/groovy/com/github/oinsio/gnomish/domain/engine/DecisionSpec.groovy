package com.github.oinsio.gnomish.domain.engine

import java.time.Instant
import spock.lang.Specification

/**
 * Decision: a single human decision carried as context (design D6). Contract:
 * {@code body} is non-blank free text; {@code stage}, {@code author} and
 * {@code time} are optional metadata the engine never interprets. Inert value
 * data compared by content. Implements FR7 of add-stage-engine.
 */
class DecisionSpec extends Specification {

    // FR7: a decision carries a free-text body plus optional stage/author/time
    def "a decision exposes body and optional metadata exactly as constructed"() {
        given: 'a moment the decision was recorded'
        def when = Instant.parse('2026-07-16T10:15:30Z')

        when: 'a decision is created with all components'
        def decision = new Decision('use library X, not Y', 'build', 'alice', when)

        then: 'each component is exposed exactly as constructed'
        decision.body() == 'use library X, not Y'
        decision.stage() == 'build'
        decision.author() == 'alice'
        decision.time() == when
    }

    // FR7: a validated body round-trips exactly — it is not silently emptied
    // (pins requireNonBlank's return against an empty-string return mutation)
    def "a validated body round-trips the constructed text"() {
        expect: 'the accessor returns the exact non-blank body it was built with'
        new Decision('approved by lead', null, null, null).body() == 'approved by lead'
    }

    // FR7: stage, author and time are optional — the engine never requires them
    def "optional metadata accepts null and is exposed as null"() {
        when: 'a decision is created with only a body'
        def decision = new Decision('ship it', null, null, null)

        then: 'the optional components are null'
        decision.body() == 'ship it'
        decision.stage() == null
        decision.author() == null
        decision.time() == null
    }

    // FR7: a decision with no body is meaningless context — rejected
    def "blank body is rejected with the component name in the message"() {
        when: 'a decision is created with a blank body'
        new Decision(body, null, null, null)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Decision.body')

        where:
        body << ['', '   ', '\t', ' \n']
    }

    // FR7: decisions are values — equal content means equal decisions
    def "decisions with the same components are equal values"() {
        given: 'a shared timestamp'
        def when = Instant.parse('2026-07-16T10:15:30Z')

        expect: 'two independently constructed decisions with equal components are equal'
        new Decision('use X', 'build', 'alice', when) ==
                new Decision('use X', 'build', 'alice', when)
    }
}
