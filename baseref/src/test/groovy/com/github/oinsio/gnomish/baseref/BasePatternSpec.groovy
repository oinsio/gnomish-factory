package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * {@link BasePattern}: the allowed-base grammar of base-ref-resolution — one metacharacter, everything else
 * literal, and a ref-name alphabet the wildcard cannot be walked out of.
 *
 * <p>FR1: patterns compile at load, so a malformed one is a located load error rather than a
 * claim-time surprise.
 *
 * <p>FR4, NFR-S3: because {@code *} crosses path separators, the syntax check is what keeps a match
 * inside the series an operator meant — a selection the project does not allow is never silently accepted.
 */
class BasePatternSpec extends Specification {

    // FR1: `*` names a series, everything else is literal — the whole grammar, in one table
    def "pattern '#pattern' #outcome ref '#refName'"() {
        expect:
        BasePattern.compile(pattern).matches(refName) == matches

        where:
        pattern | refName || matches
        'main' | 'main' || true
        'main' | 'maintenance' || false
        'main' | 'origin/main' || false
        'release/*' | 'release/1.18' || true
        'release/*' | 'release/1.18.2' || true
        'release/*' | 'release/a/b' || true
        'release/*' | 'release' || false
        'release/*' | 'hotfix/1.18' || false
        'v1.2.3' | 'v1.2.3' || true
        'v1.2.3' | 'v1a2b3' || false
        '*' | 'anything/at/all' || true
        'a*b*c' | 'axxbyyc' || true
        'a*b*c' | 'axxc' || false
        'release/1*' | 'release/2.0' || false

        outcome = matches ? 'matches' : 'does not match'
    }

    // FR4/NFR-S3: `release/*` must not accept `release/../../x` — the value is a ref name first
    def "a wildcard never matches a value that is not a well-formed ref name: '#refName'"() {
        expect:
        !BasePattern.compile('release/*').matches(refName)

        where:
        refName << [
            'release/../../etc/passwd',
            'release/ 1.18',
            'release/1.18:evil',
            'release//1.18',
            'release/1.18/',
            'release/.hidden',
            'release/1.18.lock',
            'release/x@{0}',
            'release/1.18^',
            'release/1.18~1',
            'release/1.18?',
            'release/[1]',
            'release/back\\slash',
            'release/tab\tvalue',
            'release/\u007fvalue',
            '',
            '   ',
        ]
    }

    // FR1: `*` belongs to the pattern alphabet only — a ref name that contains one names nothing
    def "a wildcard is a pattern character only, never part of a ref name"() {
        expect:
        BasePattern.compile('*').matches('release/1.18')
        !BasePattern.compile('*').matches('release/*')
    }

    // FR1: an unusable pattern is refused where it is written, naming the rule it broke
    def "compiling an invalid pattern refuses it with the violated rule: '#pattern'"() {
        when:
        BasePattern.compile(pattern)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "invalid allowed-base pattern '${pattern}': ${violation}"

        where:
        pattern || violation
        '' || 'must not be blank'
        '  ' || 'must not be blank'
        'release/ 1.18' || 'must not contain whitespace or control characters'
        'release/\t1.18' || 'must not contain whitespace or control characters'
        'release/\u007f' || 'must not contain whitespace or control characters'
        'release/1.18^' || 'must not contain any of ~^:?[\\'
        'release/1.18~' || 'must not contain any of ~^:?[\\'
        'release/1.18:' || 'must not contain any of ~^:?[\\'
        'release/1.18?' || 'must not contain any of ~^:?[\\'
        'release/[1]' || 'must not contain any of ~^:?[\\'
        'release\\1.18' || 'must not contain any of ~^:?[\\'
        'release/../x' || "must not contain '..' or '@{'"
        'release/x@{0}' || "must not contain '..' or '@{'"
        '/release' || 'must not have an empty path component'
        'release/' || 'must not have an empty path component'
        'release//1.18' || 'must not have an empty path component'
        'release/.hidden' || "no path component may start or end with '.' or end with '.lock'"
        'release/trailing.' || "no path component may start or end with '.' or end with '.lock'"
        'release/x.lock' || "no path component may start or end with '.' or end with '.lock'"
    }

    // FR1: an ordinary printable character just above the control range is legal ref-name material
    def "the character above the control range is not treated as a control character"() {
        expect:
        BasePattern.compile('release/!1.18').matches('release/!1.18')
    }

    def "a pattern is identified by its source text"() {
        given:
        def pattern = BasePattern.compile('release/*')

        expect:
        pattern.source() == 'release/*'
        pattern.toString() == 'release/*'

        and: 'two compilations of one source are the same value, so patterns compare as values'
        pattern == BasePattern.compile('release/*')

        and: 'the hash follows the source text, so patterns key a map by what the project wrote'
        pattern.hashCode() == 'release/*'.hashCode()
        pattern.hashCode() != BasePattern.compile('main').hashCode()

        and: 'a different source is a different pattern, and a foreign type is never equal'
        pattern != BasePattern.compile('release/1*')
        !pattern.equals('release/*')
    }

    def "compiling refuses a missing source outright"() {
        when:
        BasePattern.compile(null)

        then:
        thrown(NullPointerException)
    }
}
