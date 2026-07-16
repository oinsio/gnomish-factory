package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * CheckRef: the identity of a single verify check — its zero-based position in
 * the stage's ordered {@code verify} list plus a derived human-readable label of
 * the form {@code <type>:<discriminator>} (design D3). Implements FR4 of
 * add-stage-engine.
 */
class CheckRefSpec extends Specification {

    // FR4/D3: CheckRef.of derives the label per VerifyCheck variant type
    def "of derives the #expectedLabel label for each VerifyCheck variant"() {
        when: 'a CheckRef is derived from an ordered verify-list position'
        def ref = CheckRef.of(index, check)

        then: 'the index is carried through and the label is derived by type'
        ref.index() == index
        ref.label() == expectedLabel

        where:
        index | check                                                                    || expectedLabel
        0     | new VerifyCheck.Builtin('files_exist', [:])                              || 'builtin:files_exist'
        1     | new VerifyCheck.Command('./gradlew test')                                || 'command:./gradlew test'
        2     | new VerifyCheck.External('ci/build', Duration.ofSeconds(10), Duration.ofMinutes(5)) || 'external:ci/build'
        3     | new VerifyCheck.Judge('criteria/build.md', 'model-x', [:], 1)            || 'judge:criteria/build.md'
    }

    // FR4: the index and label are exposed exactly as constructed
    def "exposes index and label as constructed"() {
        when: 'a CheckRef is constructed directly'
        def ref = new CheckRef(4, 'command:make')

        then: 'both components are exposed as constructed'
        ref.index() == 4
        ref.label() == 'command:make'
    }

    // FR4/D3: a verify-list position cannot be negative — negative index is rejected
    def "rejects a negative index with the component named"() {
        when: 'a CheckRef is constructed with a negative index'
        new CheckRef(negative, 'command:make')

        then: 'construction fails and the message names the index'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CheckRef.index')

        where:
        negative << [-1, -5, Integer.MIN_VALUE]
    }

    // FR4: a zero index is the first position and is accepted
    def "accepts a zero index"() {
        when: 'a CheckRef is constructed at the first position'
        def ref = new CheckRef(0, 'builtin:files_exist')

        then: 'the zero index is exposed as constructed'
        ref.index() == 0
    }

    // FR4: the label is the check's human identity — a blank label is rejected
    def "rejects a blank label with the component named"() {
        when: 'a CheckRef is constructed with a blank label'
        new CheckRef(0, blank)

        then: 'construction fails and the message names the label'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CheckRef.label')

        where:
        blank << ['', '   ', '\t', ' \n']
    }

    // FR4: CheckRef is inert value data compared by content
    def "is value-equal by content"() {
        expect: 'two CheckRefs with the same components are equal'
        new CheckRef(2, 'external:ci/build') == new CheckRef(2, 'external:ci/build')

        and: 'differing components make them unequal'
        new CheckRef(2, 'external:ci/build') != new CheckRef(3, 'external:ci/build')
        new CheckRef(2, 'external:ci/build') != new CheckRef(2, 'external:other')
    }
}
