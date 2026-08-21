package com.github.oinsio.gnomish.app.git

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR8 of add-serve-sandbox-lifecycle (design D5): the operator override always wins over the
 * derived digest, independent of whether {@code origin} is even configured; the digest is stable
 * for a given URL and distinct across URLs; a clone with neither an override nor an {@code
 * origin} falls back to a digest of its own path, so two such clones never share one sweep scope.
 *
 * <p>Implements FR8, NFR-S1 of add-serve-sandbox-lifecycle: NFR-S1 (labels carry no credentials)
 * is enforced here, at the only seam where an {@code origin} URL — which may embed a user and a
 * token — becomes a label value.
 */
class ProjectIdentitySpec extends Specification {

    private static final Path CLONE = Path.of('/srv/clone')

    def "the override wins, even when origin is also present"() {
        expect:
        ProjectIdentity.resolve('acme-widgets', Optional.of('https://github.com/acme/widgets.git'), CLONE) ==
                'acme-widgets'
    }

    def "the override wins with no origin at all"() {
        expect:
        ProjectIdentity.resolve('acme-widgets', Optional.empty(), CLONE) == 'acme-widgets'
    }

    def "a blank override is rejected rather than silently falling back"() {
        when:
        ProjectIdentity.resolve(projectId, Optional.of('https://github.com/acme/widgets.git'), CLONE)

        then:
        thrown(IllegalArgumentException)

        where:
        projectId << ['', '   ']
    }

    def "with no override, the digest of the origin URL is stable across calls"() {
        given:
        def url = Optional.of('https://github.com/acme/widgets.git')

        expect:
        ProjectIdentity.resolve(null, url, CLONE) == ProjectIdentity.resolve(null, url, CLONE)
    }

    def "with no override, the origin digest does not depend on where the clone lives"() {
        given:
        def url = Optional.of('https://github.com/acme/widgets.git')

        expect:
        ProjectIdentity.resolve(null, url, Path.of('/srv/alpha')) ==
                ProjectIdentity.resolve(null, url, Path.of('/srv/beta'))
    }

    def "with no override, two different origin URLs derive different identities"() {
        expect:
        ProjectIdentity.resolve(null, Optional.of('https://github.com/acme/widgets.git'), CLONE) !=
                ProjectIdentity.resolve(null, Optional.of('https://github.com/acme/gadgets.git'), CLONE)
    }

    def "NFR-S1: a credential-bearing origin URL never surfaces in the derived identity"() {
        given: 'an HTTPS origin carrying an embedded user and token, the credential-bearing form'
        def url = Optional.of('https://alice:ghp_s3cr3tT0k3n@github.com/acme/widgets.git')

        when:
        def identity = ProjectIdentity.resolve(null, url, CLONE)

        then: 'the identity is a short hex digest — no user, no token, no host, no path'
        identity ==~ /[0-9a-f]{12}/

        and:
        !identity.contains('alice')
        !identity.contains('ghp_s3cr3tT0k3n')
        !identity.contains('github.com')
        !identity.contains('widgets')
    }

    def "an override outside the label-safe alphabet is rejected, naming the property"() {
        when:
        ProjectIdentity.resolve(projectId, Optional.empty(), Path.of('/srv/clone'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('factory.sandbox.project-id')

        where: 'every character docker label parsing or filtering cannot carry verbatim'
        projectId << [
            'acme,widgets',
            'acme=widgets',
            'acme widgets',
            'acme/widgets',
            'acme:widgets'
        ]
    }

    def "a forged override cannot smuggle a second label pair into the parsed label map"() {
        when: 'an override shaped to append its own mode=manual pair to the raw label string'
        ProjectIdentity.resolve('acme,com.github.oinsio.gnomish.mode=manual', Optional.empty(), Path.of('/srv/clone'))

        then:
        thrown(IllegalArgumentException)
    }

    def "an override inside the label-safe alphabet is accepted verbatim"() {
        expect:
        ProjectIdentity.resolve(projectId, Optional.empty(), Path.of('/srv/clone')) == projectId

        where:
        projectId << [
            'acme-widgets',
            'acme.widgets',
            'acme_widgets',
            'ACME0'
        ]
    }

    def "FR8: with no override and no origin, two different clones do not share one sweep scope"() {
        expect:
        ProjectIdentity.resolve(null, Optional.empty(), Path.of('/srv/alpha')) !=
                ProjectIdentity.resolve(null, Optional.empty(), Path.of('/srv/beta'))
    }

    def "with no override and no origin, the identity is a stable, non-blank digest of the clone path"() {
        given:
        def clone = Path.of('/srv/alpha')

        expect:
        ProjectIdentity.resolve(null, Optional.empty(), clone) ==~ /[0-9a-f]{12}/

        and: 'the same clone resolves identically however the path is spelled'
        ProjectIdentity.resolve(null, Optional.empty(), clone) ==
                ProjectIdentity.resolve(null, Optional.empty(), Path.of('/srv/./beta/../alpha'))
    }
}
