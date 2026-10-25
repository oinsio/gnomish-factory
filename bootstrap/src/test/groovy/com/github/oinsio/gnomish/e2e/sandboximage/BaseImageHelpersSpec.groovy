package com.github.oinsio.gnomish.e2e.sandboximage

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.environment.ImageContract
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.sandbox.environment.TestDockerCommand
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR1, FR2, FR3, NFR-S2, NFR-O1 of add-sandbox-base-image: the base's helpers, {@code
 * gnomish-trust-ca} and {@code gnomish-verify-image}, on the base itself and on an operator image
 * built {@code FROM} it ({@code gnomish-home-dir} and the home rule of design D14: {@link
 * BaseImageHomeSpec}). The operator image ({@code sandbox-base-image/Dockerfile.operator}) grafts a
 * JDK from the official image, drops the committed test certificate into the CA folder and runs
 * both helpers, as the reference recipe does; the loosening layer ({@code Dockerfile.loosened})
 * hands {@code /etc/gnomish} to the gnome before verifying. The other violations are made in a
 * throwaway container of the base, as root, so they cost no image build.
 */
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class BaseImageHelpersSpec extends Specification {

    private static final String OPERATOR_TAG = 'gnomish-sandbox-base-operator:e2e'
    private static final String LOOSENED_TAG = 'gnomish-sandbox-base-loosened:e2e'
    private static final String CONTROL_SURFACE_VIOLATION =
    'gnomish-verify-image: /etc/gnomish: owned by uid 1000; expected owned by root and not writable by gnome'

    @Shared
    String base

    @Shared
    Path context = SandboxBaseImage.repoRoot().resolve('bootstrap/src/test/resources/sandbox-base-image')

    def setupSpec() {
        base = SandboxBaseImage.ensureBuilt()
        SandboxBaseImage.buildFrom(OPERATOR_TAG, context, 'Dockerfile.operator').success()
    }

    def cleanupSpec() {
        TestDockerCommand.run([
            'image',
            'rm',
            '-f',
            OPERATOR_TAG,
            LOOSENED_TAG
        ])
    }

    // FR1, NFR-S2: a layer FROM the base that adds a JDK and an agent CLI keeps the contract.
    // Scenario: sandbox/sandbox-base-image / An operator image inherits the contract
    def "FR1, NFR-S2: an operator image built FROM the base runs as gnome behind the guard's proxy"() {
        given:
        def contract = ImageContract.current()

        when:
        def lines = TestDockerCommand.shell(OPERATOR_TAG, 'gnome',
                'id -u; id -un; echo "$HTTP_PROXY"; if test -w /etc/gnomish || touch /etc/gnomish/probe 2>/dev/null; '
                + 'then echo writable; else echo read-only; fi; for t in java claude; do command -v $t; done').success().readLines()

        then:
        lines == [
            '1000',
            'gnome',
            "http://${contract.guardAlias()}:${contract.guardPort()}".toString(),
            'read-only',
            '/opt/java/openjdk/bin/java',
            '/usr/local/bin/claude'
        ]
    }

    // FR2: the operator image ran gnomish-trust-ca after grafting the JDK; the committed certificate
    //     is in the system bundle and, by its fingerprint, in the JDK's cacerts.
    // Scenario: sandbox/sandbox-base-image / CA reaches the JVM after the JDK is grafted
    def "FR2: a dropped certificate reaches the system and the JVM trust stores"() {
        given:
        def pem = Files.readString(context.resolve('test-ca.crt')).strip()

        when:
        def bundle = TestDockerCommand.shell(OPERATOR_TAG, 'gnome', 'cat /etc/ssl/certs/ca-certificates.crt').success()
        def jvm = TestDockerCommand.shell(OPERATOR_TAG, 'gnome', 'keytool -list -cacerts -storepass changeit').success()

        then:
        bundle.contains(pem)
        jvm.contains(sha256Fingerprint(context.resolve('test-ca.crt')))
    }

    // FR2: with the folder emptied, a run as root succeeds and leaves both stores byte for byte.
    // Scenario: sandbox/sandbox-base-image / An empty CA folder is a no-op
    def "FR2: gnomish-trust-ca with an empty CA folder succeeds and changes neither trust store"() {
        given:
        def stores = 'ls -l --time-style=full-iso /etc/ssl/certs /usr/local/share/ca-certificates; ' +
                'sha256sum /etc/ssl/certs/ca-certificates.crt "$JAVA_HOME/lib/security/cacerts"'

        when:
        def lines = TestDockerCommand.shell(OPERATOR_TAG, 'root', """
            rm /etc/gnomish/ca/*
            { ${stores}; } > /tmp/before
            gnomish-trust-ca > /tmp/out 2>&1; echo "exit=\$?"
            { ${stores}; } > /tmp/after
            cmp -s /tmp/before /tmp/after && echo unchanged || echo changed
            cat /tmp/out""").success().readLines()

        then:
        lines[0] == 'exit=0'
        lines[1] == 'unchanged'
        lines[2] == 'gnomish-trust-ca: no *.pem or *.crt file in /etc/gnomish/ca; nothing to import'
    }

    // FR2, FR3: the helpers are root-owned and executable for every user; the base and the operator
    //     image both pass the verification (each also ran it at its own build); BaseImageHomeSpec runs
    //     it on the base as root as well.
    def "FR2, FR3: the helpers are root-owned 0755 and gnomish-verify-image passes on the base and the operator image"() {
        expect:
        TestDockerCommand.shell(base, 'gnome', 'stat -c "%U %G %a %n" /usr/local/bin/gnomish-*').success().readLines() == [
            'root root 755 /usr/local/bin/gnomish-home-dir',
            'root root 755 /usr/local/bin/gnomish-trust-ca',
            'root root 755 /usr/local/bin/gnomish-verify-image'
        ]
        TestDockerCommand.shell(image, 'gnome', 'gnomish-verify-image').exitCode == 0

        where:
        image << [
            SandboxBaseImage.TAG,
            OPERATOR_TAG
        ]
    }

    // FR3, NFR-O1: an operator layer that hands /etc/gnomish to the gnome fails its own build, and the
    //     build's output names the path and the expected state.
    // Scenario: sandbox/sandbox-base-image / A loosened control surface fails the build
    def "FR3, NFR-O1: a layer that makes /etc/gnomish writable by gnome fails its build naming the surface"() {
        when:
        def result = SandboxBaseImage.buildFrom(LOOSENED_TAG, context, 'Dockerfile.loosened')

        then:
        result.exitCode != 0
        result.output.contains(CONTROL_SURFACE_VIOLATION)
    }

    // FR3, NFR-O1: each violation is one line naming its path, what was found, the expected state and
    //     the fix; with more than one, every one is listed and counted. The home and the agent policy
    //     have violations of their own, listed the same way (BaseImageHomeSpec).
    // Scenario: sandbox/sandbox-base-image / Every violation is listed
    def "FR3, NFR-O1: gnomish-verify-image lists every violation (#breakage)"() {
        when:
        def result = TestDockerCommand.shell(base, 'root', "${breakage}; gnomish-verify-image")

        then:
        result.exitCode == 1
        def violations = result.output.readLines().findAll {
            it.startsWith('gnomish-verify-image: ') && it.contains('; fix: ')
        }
        violations.size() == expected.size()
        expected.every { line -> violations.any { it.startsWith(line) } }
        result.output.contains("gnomish-verify-image: ${expected.size()} violation(s) of the image contract")

        where:
        breakage || expected
        'chown gnome /etc/gnomish' || [CONTROL_SURFACE_VIOLATION]
        'rm "$(command -v curl)"' || [CURL_VIOLATION]
        'chown gnome /etc/gnomish && rm "$(command -v curl)"' || [
            CURL_VIOLATION,
            CONTROL_SURFACE_VIOLATION
        ]
        'chmod o+w /etc/claude-code/managed-settings.json' || [POLICY_VIOLATION]
    }

    private static final String CURL_VIOLATION = 'gnomish-verify-image: curl: not found on PATH; expected curl on PATH'
    private static final String POLICY_VIOLATION =
    'gnomish-verify-image: /etc/claude-code/managed-settings.json: mode 646; expected owned by root and not writable by gnome'

    /** The certificate's SHA-256 fingerprint as keytool prints it: upper-case hex pairs joined by colons. */
    private static String sha256Fingerprint(Path pem) {
        def certificate = Files.newInputStream(pem).withCloseable {
            CertificateFactory.getInstance('X.509').generateCertificate(it)
        }
        MessageDigest.getInstance('SHA-256').digest(certificate.encoded).collect {
            String.format('%02X', it)
        }.join(':')
    }
}
