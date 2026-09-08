package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * {@link AllowedBases}, {@link AllowedBase} and {@link BranchRole}: the list that governs which refs a
 * task may branch from.
 *
 * <p>FR1: a list of patterns, each with a role of {@code development} or {@code release} defaulting
 * to development; an absent section declares no allowed base and no configured default.
 *
 * <p>FR4: a selection matching no pattern is rejected, never silently replaced — including when the
 * list is empty, which accepts nothing at all.
 */
class AllowedBasesSpec extends Specification {

    private static BasePattern pattern(String source) {
        BasePattern.compile(source)
    }

    // FR1: role defaults to development — the zero-configuration project branches from its trunk
    def "a allowed base declaring no role is a development entry"() {
        expect:
        AllowedBase.of(pattern('main')).role() == BranchRole.DEVELOPMENT
        BranchRole.defaultRole() == BranchRole.DEVELOPMENT

        and: 'the vocabulary is exactly the two named roles'
        BranchRole.values() as List == [
            BranchRole.DEVELOPMENT,
            BranchRole.RELEASE
        ]
    }

    def "a allowed base carries the declared role and pattern"() {
        given:
        def entry = new AllowedBase(pattern('release/*'), BranchRole.RELEASE)

        expect:
        entry.role() == BranchRole.RELEASE
        entry.pattern().source() == 'release/*'
    }

    def "a allowed base refuses a missing component"() {
        when:
        new AllowedBase(entryPattern, role)

        then:
        thrown(NullPointerException)

        where:
        entryPattern | role
        null | BranchRole.RELEASE
        BasePattern.compile('a') | null
    }

    // FR4: the match answers with the entry, so a decision can record the rule that accepted it
    def "the allowed bases answer with the first entry whose pattern accepts the ref"() {
        given: 'three entries, two of which accept release/1.18'
        def allowedBases = AllowedBases.of([
            AllowedBase.of(pattern('main')),
            new AllowedBase(pattern('release/*'), BranchRole.RELEASE),
            AllowedBase.of(pattern('*')),
        ])

        expect: 'declaration order decides, not specificity'
        allowedBases.match('release/1.18').get() == new AllowedBase(pattern('release/*'), BranchRole.RELEASE)
        allowedBases.match('main').get() == AllowedBase.of(pattern('main'))
        allowedBases.match('anything').get() == AllowedBase.of(pattern('*'))
    }

    def "the allowed bases answer empty for a ref no entry accepts"() {
        given:
        def allowedBases = AllowedBases.of([
            AllowedBase.of(pattern('release/*'))
        ])

        expect:
        allowedBases.match('experiments/foo').isEmpty()

        and: 'a declared list is not the empty one'
        !allowedBases.isEmpty()
        allowedBases.entries() == [
            AllowedBase.of(pattern('release/*'))
        ]
    }

    // FR1/FR4: an empty list accepts nothing — the zero-configuration state, stated as a value
    def "the empty list of allowed bases accepts nothing and says so"() {
        expect:
        AllowedBases.empty().isEmpty()
        AllowedBases.empty().entries().isEmpty()
        AllowedBases.empty().match('main').isEmpty()
        AllowedBases.empty().describe() == '(empty)'

        and: 'a declared list with no entries is the same state'
        AllowedBases.of([]).isEmpty()
        AllowedBases.of([]).match('main').isEmpty()
        AllowedBases.of([]).describe() == '(empty)'
    }

    // UX2: the report shows the allowed bases the human has to fix, in the order they wrote them
    def "the allowed bases describe themselves in declaration order"() {
        expect:
        AllowedBases.of([
            AllowedBase.of(pattern('main')),
            new AllowedBase(pattern('release/*'), BranchRole.RELEASE),
        ]).describe() == 'main, release/*'

        and: 'a single entry needs no separator'
        AllowedBases.of([
            AllowedBase.of(pattern('main'))
        ]).describe() == 'main'
    }

    def "the allowed bases are a value: they copy what they are given and hand back nothing mutable"() {
        given:
        def entries = [
            AllowedBase.of(pattern('main'))
        ]
        def allowedBases = AllowedBases.of(entries)

        when: 'the caller keeps mutating its own list'
        entries.add(AllowedBase.of(pattern('release/*')))

        then: 'the allowed bases are unchanged'
        allowedBases.entries() == [
            AllowedBase.of(pattern('main'))
        ]

        when:
        allowedBases.entries().add(AllowedBase.of(pattern('other')))

        then:
        thrown(UnsupportedOperationException)
    }

    def "the allowed bases refuse a missing entry list or a missing ref name"() {
        when:
        AllowedBases.of(null)

        then:
        thrown(NullPointerException)

        when:
        AllowedBases.empty().match(null)

        then:
        thrown(NullPointerException)
    }
}
