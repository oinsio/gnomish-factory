package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * {@link BaseDefinition}: the project's declared base policy — the allowed bases and the configured default —
 * as one value the funnel carries.
 *
 * <p>FR1: a project without a {@code task-branch.base} section declares no allowed base and no default.
 *
 * <p>FR2: the pair is bound once from the trusted tier, so it travels together rather than as two
 * loose arguments a caller could mismatch.
 */
class BaseDefinitionSpec extends Specification {

    // FR1: an absent section is no allowed base with no configured default
    def "the zero-configuration definition has no allowed base and no default"() {
        given:
        def definition = BaseDefinition.none()

        expect:
        definition.allowedBases().isEmpty()
        definition.defaultRef() == null

        and: 'it is the same value every time — nothing about it varies per project'
        BaseDefinition.none().is(definition)
    }

    def "a declared definition exposes its allowed bases and its default"() {
        given:
        def allowedBases = AllowedBases.of([
            AllowedBase.of(BasePattern.compile('release/*'))
        ])

        when:
        def definition = new BaseDefinition(allowedBases, 'main')

        then:
        definition.allowedBases().is(allowedBases)
        definition.defaultRef() == 'main'
    }

    def "a definition without allowed bases is refused"() {
        when:
        new BaseDefinition(null, 'main')

        then:
        def error = thrown(NullPointerException)
        error.message == 'allowedBases'
    }
}
