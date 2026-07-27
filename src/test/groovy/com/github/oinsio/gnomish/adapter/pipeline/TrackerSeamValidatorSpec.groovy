package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import spock.lang.Specification

/**
 * TrackerSeamValidator (FR17 of add-tracker-port, design D5): the core loader
 * knows only two tracker keys (type, abort-threshold, task 3.1) and delegates
 * the adapter-owned subsection's schema to a registered
 * {@link TrackerSubsectionValidator} — but the loader itself still owns and
 * verifies the seam around that delegation: an unknown {@code type}, a
 * declared {@code type} with no matching subsection, and a stray subsection
 * that does not match {@code type} are all located errors reported here,
 * never silently dropped. Errors returned by a registered adapter validator
 * are threaded straight into the same list, so they aggregate with core
 * errors under the loader's single-pass contract (task 6.5).
 *
 * Implements FR17 of add-tracker-port.
 */
class TrackerSeamValidatorSpec extends Specification {

    private static final String FILE = 'config.yaml'

    def "no tracker declared at all: nothing to check, no errors"() {
        expect:
        TrackerSeamValidator.validate(FILE, null, [:]) == []
    }

    def "a present section with no type reports a located missing-type error, contract of TrackerConfig/TrackerDto"() {
        given: 'a tracker section present but with type omitted, even carrying a subsection'
        def tracker = new TrackerDto(null, null, [github: [url: 'https://api.github.com']])
        def registry = [github: okValidator()]

        when:
        def errors = TrackerSeamValidator.validate(FILE, tracker, registry)

        then: 'the sole error names tracker.type — never a misleading "does not match type null"'
        errors == [
            new ConfigError(FILE, 'tracker.type', 'missing required tracker type')
        ]
    }

    def "an unknown type reports a located error naming it, before any subsection check"() {
        given:
        def tracker = new TrackerDto('bogus', null, [:])

        when:
        def errors = TrackerSeamValidator.validate(FILE, tracker, [:])

        then:
        errors == [
            new ConfigError(FILE, 'tracker.type', "unknown tracker type 'bogus'")
        ]
    }

    def "a known type with no matching subsection reports a located missing-subsection error"() {
        given:
        def tracker = new TrackerDto('github', null, [:])
        def registry = [github: okValidator()]

        when:
        def errors = TrackerSeamValidator.validate(FILE, tracker, registry)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github', "missing required subsection 'github'")
        ]
    }

    def "a matching subsection plus a stray subsection reports the stray one, not the matching one"() {
        given:
        def tracker = new TrackerDto('github', null, [github: [:], jira: [:]])
        def registry = [github: okValidator()]

        when:
        def errors = TrackerSeamValidator.validate(FILE, tracker, registry)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.jira',
            "subsection 'jira' does not match declared tracker type 'github'")
        ]
    }

    def "a valid type with a matching, adapter-accepted subsection reports no errors"() {
        given:
        def tracker = new TrackerDto('github', null, [github: [url: 'https://api.github.com']])
        def registry = [github: okValidator()]

        expect:
        TrackerSeamValidator.validate(FILE, tracker, registry) == []
    }

    def "adapter validator errors are threaded through unchanged, located under the subsection"() {
        given: 'a fake validator that rejects a subsection missing a required "url" key'
        def tracker = new TrackerDto('github', null, [github: [:]])
        def registry = [github: rejectingValidator()]

        when:
        def errors = TrackerSeamValidator.validate(FILE, tracker, registry)

        then:
        errors == [
            new ConfigError(FILE, 'tracker.github', "missing required key 'url'")
        ]
    }

    def "the subsection content handed to the validator is the actual parsed map, not an empty stand-in"() {
        given: 'a validator that reports back whatever keys it actually received'
        def tracker = new TrackerDto('github', null, [github: [url: 'https://api.github.com', token: 'secret']])
        Map<String, Object> seen = null
        TrackerSubsectionValidator capturingValidator = { file, where, subsection ->
            seen = subsection
            []
        }
        def registry = [github: capturingValidator]

        when:
        TrackerSeamValidator.validate(FILE, tracker, registry)

        then:
        seen == [url: 'https://api.github.com', token: 'secret']
    }

    private static TrackerSubsectionValidator okValidator() {
        (file, where, subsection) -> []
    }

    private static TrackerSubsectionValidator rejectingValidator() {
        (file, where, subsection) -> subsection.containsKey('url')
        ? []
        : [
            new ConfigError(file, where, "missing required key 'url'")
        ]
    }
}
