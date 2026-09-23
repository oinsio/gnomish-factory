package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR10, NFR-R2 of own-git-transfer-argv: the installed git version is a value the startup check
 * compares against the leaf's floor — parsed leniently from {@code git --version}'s one line, so a
 * vendor suffix does not refuse a good git, while a line that names no version yields nothing and
 * the check refuses it the same way as one below the floor.
 */
class GitVersionSpec extends Specification {

    // FR10: the floor is the release whose local-clone protections the seed clone depends on.
    def "the floor is 2.45.1 with its reason"() {
        expect:
        GitVersion.FLOOR == new GitVersion(2, 45, 1)
        GitVersion.FLOOR.toString() == '2.45.1'
        GitVersion.FLOOR_REASON.contains('local-clone')
        GitVersion.FLOOR_REASON.contains('2.45.1')
    }

    // FR10: lenient parse — the three numbers after `git version`, anything after them ignored.
    def "a version line parses to its three numbers, suffixes ignored"() {
        expect:
        GitVersion.parse(line) == Optional.of(new GitVersion(major, minor, patch))

        where:
        line || major | minor | patch
        'git version 2.45.1' || 2 | 45 | 1
        'git version 2.55.0 (Apple Git-200)' || 2 | 55 | 0
        'git version 2.44.0.windows.1' || 2 | 44 | 0
        'git version 2.39.5' || 2 | 39 | 5
        'git version 3.0.0-rc1' || 3 | 0 | 0
        'git version 2.45' || 2 | 45 | 0
        'git version 2.45.1\n' || 2 | 45 | 1
        '  git version 2.45.1  ' || 2 | 45 | 1
    }

    // NFR-R2: a git that cannot report its version is refused the same way as one below the
    //     floor — the parse yields nothing rather than guessing.
    def "a blank or garbage line yields nothing"() {
        expect:
        GitVersion.parse(line) == Optional.empty()

        where:
        line << [
            '',
            '   ',
            'git version',
            'git version x.y.z',
            'gits version 2.45.1',
            'version 2.45.1',
            'git: command not found',
            'git version 2',
            'git version .45.1',
        ]
    }

    def "a missing line is refused outright"() {
        when:
        GitVersion.parse(null)

        then:
        thrown(NullPointerException)
    }

    // FR10: comparison is numeric and component-wise, never textual.
    def "versions compare component-wise"() {
        expect:
        new GitVersion(a1, a2, a3).isBelow(new GitVersion(b1, b2, b3)) == below
        (new GitVersion(a1, a2, a3) <=> new GitVersion(b1, b2, b3)) == Integer.signum(sign)

        where:
        a1 | a2 | a3 | b1 | b2 | b3 || below | sign
        2 | 44 | 9 | 2 | 45 | 1 || true | -1
        2 | 45 | 0 | 2 | 45 | 1 || true | -1
        2 | 45 | 1 | 2 | 45 | 1 || false | 0
        2 | 45 | 2 | 2 | 45 | 1 || false | 1
        2 | 55 | 0 | 2 | 45 | 1 || false | 1
        3 | 0 | 0 | 2 | 45 | 1 || false | 1
        1 | 99 | 99 | 2 | 45 | 1 || true | -1
        2 | 9 | 99 | 2 | 45 | 1 || true | -1
    }

    def "a negative component is refused"() {
        when:
        new GitVersion(major, minor, patch)

        then:
        thrown(IllegalArgumentException)

        where:
        major | minor | patch
        -1 | 0 | 0
        2 | -1 | 0
        2 | 45 | -1
    }

    def "a version renders as dotted numbers"() {
        expect:
        new GitVersion(2, 55, 0).toString() == '2.55.0'
    }
}
