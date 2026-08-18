package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * {@link ConnectionProfiles}: the named per-vendor connection profiles an operator declares as
 * {@code factory.connections.<name>}, and the single engine-owned key — {@code connection} — a port
 * subsection references one by (FR16, UX3, design D8 of add-plugin-architecture).
 *
 * <p>Two things are specified here and nowhere else: what a referencing subsection <em>resolves
 * to</em>, since every provider downstream reads that resolved shape and none of them learns a
 * profile was involved; and what makes a reference itself wrong — malformed, undefined, or declared
 * alongside an inline key the profile already carries.
 *
 * <p>FR16: shared connection data, per-port provider selection.
 */
class ConnectionProfilesSpec extends Specification {

    private static final String FILE = 'application.yaml'
    private static final String WHERE = 'factory.check.github'

    private static final Map GITHUB_MAIN = [('api-url'): 'https://api.github.com', credential: 'GNOMISH_GH_MAIN']

    private static ConnectionProfiles profiles() {
        ConnectionProfiles.of(['github-main': GITHUB_MAIN])
    }

    // FR16: the defined set is what a validator names back to an operator who mistyped a reference.
    def "the defined profile names are exposed, and none() defines none"() {
        expect:
        profiles().names() == ['github-main'] as Set
        ConnectionProfiles.none().names().isEmpty()
        ConnectionProfiles.of([:]).names().isEmpty()
    }

    // FR16: a profile's content is copied in, so a later mutation of the operator's map cannot
    //     change what a subsection resolves to mid-run.
    def "profile content is copied defensively, and a null profile body is an empty one"() {
        given:
        def source = ['github-main': [('api-url'): 'https://api.github.com'], empty: null]
        def copied = ConnectionProfiles.of(source)

        when:
        source['github-main'] = [('api-url'): 'https://evil.example']

        then:
        copied.resolve([connection: 'github-main'])['api-url'] == 'https://api.github.com'
        copied.resolve([connection: 'empty']) == [:]
    }

    // FR16: only a non-blank string names a profile; anything else is not a reference — it is a
    //     malformed one, which validateReference reports rather than silently honouring.
    def "a reference is a non-blank string and nothing else"() {
        expect:
        ConnectionProfiles.referenceIn([connection: 'github-main']) == Optional.of('github-main')
        ConnectionProfiles.referenceIn([connection: '  ']).isEmpty()
        ConnectionProfiles.referenceIn([connection: 42]).isEmpty()
        ConnectionProfiles.referenceIn([('api-url'): 'https://api.github.com']).isEmpty()
    }

    // FR16: an inline subsection is untouched — the shape every provider already reads. The
    //     identity check matters: no copy means no chance of a re-ordered or re-typed map.
    def "an inline subsection resolves to itself"() {
        given:
        def subsection = [('api-url'): 'https://api.github.com', repo: 'acme/widgets']

        expect:
        profiles().resolve(subsection).is(subsection)
    }

    // FR16, D8: the resolved view is the profile's keys plus the subsection's own, with the
    //     reference key itself gone — so `repo` may stay per-port while the endpoint is shared.
    def "a referencing subsection resolves to the profile's keys plus its own"() {
        when:
        def resolved = profiles().resolve([connection: 'github-main', repo: 'acme/widgets'])

        then:
        resolved == [('api-url'): 'https://api.github.com', credential: 'GNOMISH_GH_MAIN', repo: 'acme/widgets']
        !resolved.containsKey('connection')
    }

    // FR16: a key declared on both sides is a validateReference error, so resolution never has to
    //     arbitrate — but it is defined anyway, and the subsection wins as the more specific one.
    def "an inline key overrides the profile's own"() {
        expect:
        profiles().resolve([connection: 'github-main', ('api-url'): 'https://ghe.acme.test'])['api-url'] ==
        'https://ghe.acme.test'
    }

    // FR16: resolution is deliberately lenient on a name nobody defined — validation has already
    //     reported it as a located error, so the provider simply finds its key missing.
    def "an undefined or malformed reference resolves to the subsection without it"() {
        expect:
        profiles().resolve([connection: 'nope', repo: 'acme/widgets']) == [repo: 'acme/widgets']
        profiles().resolve([connection: 42, repo: 'acme/widgets']) == [repo: 'acme/widgets']
    }

    // FR16: no reference, nothing to grade — an operator who never heard of profiles sees nothing.
    def "a subsection declaring no reference has nothing to report"() {
        expect:
        profiles().validateReference(FILE, WHERE, [('api-url'): 'https://api.github.com']).isEmpty()
        profiles().validateReference(FILE, WHERE, [connection: 'github-main', repo: 'acme/widgets']).isEmpty()
    }

    // FR16, "Undefined profile name is a located error": the error names the missing profile AND the
    //     defined set, so a typo is fixed from the message alone.
    def "an undefined profile reference is a located error naming it and the defined set"() {
        when:
        def errors = profiles().validateReference(FILE, WHERE, [connection: 'nope'])

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == 'factory.check.github.connection'
        errors[0].message().contains("undefined connection profile 'nope'")
        errors[0].message().contains('github-main')
    }

    // FR16: a reference that is not a name at all is its own located error, never treated as absent.
    def "a malformed reference value is a located error"() {
        expect:
        profiles().validateReference(FILE, WHERE, [connection: value]) == [
            new ConfigError(FILE, 'factory.check.github.connection', 'connection must be a non-blank profile name')
        ]

        where:
        value << ['', '   ', 42, [:]]
    }

    // FR16, "Ambiguous connection declaration is a located error": declaring both forms is reported
    //     per offending key, in key order, so an operator sees every duplicate at once.
    def "declaring both a reference and an inline key the profile carries is a located error per key"() {
        when:
        def errors = profiles().validateReference(FILE, WHERE,
                [connection: 'github-main', ('api-url'): 'https://ghe.acme.test', credential: 'OTHER',
                    repo: 'acme/widgets'])

        then: 'the two keys the profile also defines, in key order; repo is not one of them'
        errors*.where() == [
            'factory.check.github.api-url',
            'factory.check.github.credential'
        ]
        errors*.message().every {
            it.contains("declares both 'connection: github-main'")
        }
    }

    // FR16, design D12: the SPI's connection-aware validator form resolves the reference and hands
    //     the adapter one shape, so an adapter states its key rules once for both forms — and the
    //     adapter's own verdict travels back out, or the seam would aggregate silence.
    def "the tracker and check validator defaults grade the resolved subsection and return its verdict"() {
        given: 'validators that report back whichever endpoint they were handed'
        TrackerSubsectionValidator tracker = { String file, String where, Map subsection ->
            [
                new ConfigError(file, where, "tracker saw '${subsection['api-url']}'".toString())
            ]
        }
        CheckSubsectionValidator check = { String file, String where, Map subsection ->
            [
                new ConfigError(file, where, "check saw '${subsection['api-url']}'".toString())
            ]
        }

        when:
        def trackerErrors = tracker.validate(FILE, WHERE, [connection: 'github-main'], profiles())
        def checkErrors = check.validate(FILE, WHERE, [connection: 'github-main'], profiles())

        then: 'both graded the profile-resolved endpoint, and their verdicts reached the caller'
        trackerErrors == [
            new ConfigError(FILE, WHERE, "tracker saw 'https://api.github.com'")
        ]
        checkErrors == [
            new ConfigError(FILE, WHERE, "check saw 'https://api.github.com'")
        ]
    }

    // FR16: an inline subsection reaches the same validator untouched, so an adapter that never
    //     heard of profiles keeps grading exactly what the operator wrote.
    def "the validator defaults pass an inline subsection through unchanged"() {
        given:
        CheckSubsectionValidator check = { String file, String where, Map subsection ->
            [
                new ConfigError(file, where, "check saw '${subsection['api-url']}'".toString())
            ]
        }

        expect:
        check.validate(FILE, WHERE, [('api-url'): 'https://ghe.acme.test'], ConnectionProfiles.none()) ==
        [
            new ConfigError(FILE, WHERE, "check saw 'https://ghe.acme.test'")
        ]
    }
}
