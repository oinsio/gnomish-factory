package com.github.oinsio.gnomish.app.git

import java.nio.file.Path
import spock.lang.Specification

/**
 * The write side of the project identity: what a factory stamps on the objects it creates, and
 * what it says when the operator's override cannot be stamped. Companion to {@code
 * ProjectIdentitySpec} (derivation and scope); the read side of a legacy identity is {@code
 * SandboxLifecycleNoRelabelSpec} in {@code sandbox/docker}.
 *
 * <p>FR1, FR3, NFR-S1 of normalize-project-identity-url; FR8 of add-serve-sandbox-lifecycle.
 */
class ProjectIdentityStampSpec extends Specification {

    private static final Path CLONE = Path.of('/srv/clone')

    // FR3: resolve() is the stamp every creation path takes (ContainerRunSupportFactory); while a
    //     legacy identity exists it is the normalized identity, never the legacy one
    // Scenario: sandbox/sandbox-lifecycle / Objects are stamped with the normalized identity and never relabelled
    def "FR3: the identity a new object is stamped with is the normalized one, even while a legacy identity exists"() {
        given:
        def raw = Optional.of('https://alice:ghp_t0k3n@GitHub.com:443/acme/widgets.git/')
        def scope = ProjectIdentity.resolveScope(null, raw, CLONE)
        assert scope.legacyIdentity().isPresent()

        expect:
        ProjectIdentity.resolve(null, raw, CLONE) == scope.identity()
        ProjectIdentity.resolve(null, raw, CLONE) != scope.legacyIdentity().get()
    }

    // NFR-S1: the refusal names the property and keeps the operator's value out of the message
    // Scenario: sandbox/sandbox-lifecycle / An override outside the label-safe set is refused without its value
    def "NFR-S1: an override outside [A-Za-z0-9._-]+ is refused naming the property, the value not echoed"() {
        when:
        ProjectIdentity.resolve(projectId, Optional.empty(), CLONE)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('factory.sandbox.project-id')
        !e.message.contains(projectId.trim())

        where:
        projectId << [
            'acme,mode=manual',
            'secret=value',
            'tok en42',
            'acme/widgets',
            'ghp_s3cr3t:x',
        ]
    }
}
