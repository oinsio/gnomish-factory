package com.github.oinsio.gnomish.app.git

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR2, NFR-R1, NFR-S1 of normalize-project-identity-url (M1): the credential and cosmetic
 * spellings of one remote fold to one value; genuinely distinct remotes stay apart; anything the
 * two recognized shapes do not match comes back unchanged rather than throwing.
 */
class OriginUrlNormalizerSpec extends Specification {

    /** The one spelling every variant of the acme/widgets remote is expected to fold to. */
    private static final String CANONICAL = 'https://github.com/acme/widgets'

    // FR1, M1: every credential and cosmetic variant of one remote normalizes to one spelling.
    def "FR1: cosmetic and credential variants of one remote fold to one spelling: #variant"() {
        expect:
        OriginUrlNormalizer.normalize(variant) == CANONICAL

        where:
        variant << [
            'https://github.com/acme/widgets',
            'https://github.com/acme/widgets.git',
            'https://github.com/acme/widgets/',
            'https://github.com/acme/widgets.git/',
            'https://ghp_s3cr3tT0k3n@github.com/acme/widgets.git',
            'https://alice:ghp_s3cr3tT0k3n@github.com/acme/widgets.git',
            'https://x-access-token:ghs_ROTATED@github.com/acme/widgets.git',
            'https://GitHub.com/acme/widgets.git',
            'HTTPS://github.com/acme/widgets.git',
            'https://github.com:443/acme/widgets.git'
        ]
    }

    // FR1: the scp-style form folds into the shape of its ssh:// equivalent, credential-free.
    def "FR1: the scp-style form folds to the shape of the equivalent ssh URL: #variant"() {
        expect:
        OriginUrlNormalizer.normalize(variant) == 'ssh://github.com/acme/widgets'

        where:
        variant << [
            'git@github.com:acme/widgets.git',
            'git@github.com:acme/widgets',
            'git@GitHub.com:acme/widgets.git/',
            'github.com:acme/widgets.git',
            'ssh://git@github.com/acme/widgets.git',
            'ssh://github.com:22/acme/widgets'
        ]
    }

    // FR1: a rotated credential — the motivating case — leaves the spelling untouched.
    def "FR1: rotating an embedded credential does not change the normalized URL"() {
        expect:
        OriginUrlNormalizer.normalize('https://ghp_OLDTOKEN@github.com/acme/widgets.git') ==
                OriginUrlNormalizer.normalize('https://ghp_NEWTOKEN@github.com/acme/widgets.git')
    }

    // FR2: normalization is not a scope-sharing policy — a real difference stays a difference.
    def "FR2: remotes differing in #difference keep distinct spellings"() {
        expect:
        OriginUrlNormalizer.normalize(other) != CANONICAL

        and: 'and the distinct spelling is itself normalized, not merely passed through'
        OriginUrlNormalizer.normalize(other) == OriginUrlNormalizer.normalize(other + '.git')

        where:
        difference | other
        'host' | 'https://gitlab.com/acme/widgets'
        'owner' | 'https://github.com/other/widgets'
        'repository' | 'https://github.com/acme/gadgets'
        'path depth' | 'https://github.com/acme/group/widgets'
        'non-default port' | 'https://github.com:8443/acme/widgets'
        'scheme' | 'http://github.com/acme/widgets'
    }

    // FR2: a non-default port is identity-bearing; only the scheme's own default is redundant.
    def "FR2: only the scheme's own default port is dropped"() {
        expect:
        OriginUrlNormalizer.normalize('http://github.com:80/acme/widgets') == 'http://github.com/acme/widgets'
        OriginUrlNormalizer.normalize('git://github.com:9418/acme/widgets') == 'git://github.com/acme/widgets'

        and: "another scheme's default is not this scheme's default"
        OriginUrlNormalizer.normalize('https://github.com:80/acme/widgets') == 'https://github.com:80/acme/widgets'
    }

    // NFR-R1: total function — an unrecognized shape is returned verbatim and never throws.
    def "NFR-R1: an unrecognized remote shape is returned unchanged: '#url'"() {
        expect:
        OriginUrlNormalizer.normalize(url) == url

        where:
        url << [
            '',
            '   ',
            '/srv/repos/widgets.git',
            '../sibling/widgets.git',
            'ext::git-remote-helper %S widgets',
            'file:///srv/repos/widgets.git',
            'https:///acme/widgets.git',
            'ssh://[::1]/repos/widgets.git',
            'https://[2001:db8::1]:8443/acme/widgets.git',
            'not a url at all'
        ]
    }

    // NFR-S1: the stripped userinfo must not survive anywhere the output travels.
    def "NFR-S1: no removed userinfo survives into the normalized URL or the derived identity"() {
        given:
        def secret = 'ghp_s3cr3tT0k3n'
        def url = "https://alice:${secret}@github.com/acme/widgets.git".toString()

        when:
        def normalized = OriginUrlNormalizer.normalize(url)
        def identity = ProjectIdentity.resolve(null, Optional.of(url), Path.of('/srv/clone'))

        then:
        !normalized.contains(secret)
        !normalized.contains('alice')
        normalized == CANONICAL

        and:
        identity ==~ /[0-9a-f]{12}/
        !identity.contains(secret)
    }

    // NFR-R1: whatever the shape, the normalizer answers rather than throwing — an unusual remote
    // costs identity stability, never a run or a sweep pass.
    def "NFR-R1: a degenerate shape never throws: '#url'"() {
        when:
        def normalized = OriginUrlNormalizer.normalize(url)

        then:
        noExceptionThrown()
        normalized != null

        where:
        url << [
            '@',
            ':',
            '://',
            'a@b@c',
            'http://',
            'git@:',
            ' odd'
        ]
    }
}
