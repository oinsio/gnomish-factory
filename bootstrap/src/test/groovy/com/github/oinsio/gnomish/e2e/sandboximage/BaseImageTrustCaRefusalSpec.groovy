package com.github.oinsio.gnomish.e2e.sandboximage

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.environment.SandboxBaseImage
import com.github.oinsio.gnomish.sandbox.environment.TestDockerCommand
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

/**
 * FR2, NFR-O1 of add-sandbox-base-image: the refusals of {@code gnomish-trust-ca}. The helper names
 * the JVM alias of a certificate after its file, and {@code keytool} folds aliases to lower case, so
 * two drop files whose names differ only in letter case would silently replace each other in the
 * JVM store; the helper refuses them before it writes anything, whether or not {@code keytool} is on
 * {@code PATH}. The refusal is made in a throwaway container of the base, as root, with no image
 * build; the imports themselves are specified in {@link BaseImageHelpersSpec}.
 */
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class BaseImageTrustCaRefusalSpec extends Specification {

    @Shared
    String base

    @Shared
    Path context = SandboxBaseImage.repoRoot().resolve('bootstrap/src/test/resources/sandbox-base-image')

    def setupSpec() {
        base = SandboxBaseImage.ensureBuilt()
    }

    // FR2, NFR-O1: keytool folds aliases to lower case (verified on JDK 25: `-delete -alias
    //     gnomish-ca.crt-1` removes the entry imported as `gnomish-CA.crt-1`), so the second file's
    //     delete-then-import would silently drop the first file's certificates from the JVM store. The
    //     helper refuses the pair, names both files and the fix, and leaves both stores untouched.
    // Scenario: sandbox/sandbox-base-image / Two CA files whose names differ only in letter case are refused
    def "FR2, NFR-O1: two CA files whose names differ only in letter case are refused before any import"() {
        given:
        def pem = Files.readString(context.resolve('test-ca.crt')).strip()
        def stores = 'ls -lR --time-style=full-iso /etc/ssl/certs /usr/local/share/ca-certificates; ' +
                'sha256sum /etc/ssl/certs/ca-certificates.crt'

        when:
        def lines = TestDockerCommand.shell(base, 'root', """
            printf '%s\\n' '${pem}' > /etc/gnomish/ca/Corp-CA.crt
            cp /etc/gnomish/ca/Corp-CA.crt /etc/gnomish/ca/corp-ca.crt
            { ${stores}; } > /tmp/before
            gnomish-trust-ca > /tmp/out 2>&1; echo "exit=\$?"
            { ${stores}; } > /tmp/after
            cmp -s /tmp/before /tmp/after && echo unchanged || echo changed
            cat /tmp/out""").success().readLines()

        then:
        lines[0] == 'exit=1'
        lines[1] == 'unchanged'
        lines[2] == 'gnomish-trust-ca: /etc/gnomish/ca/corp-ca.crt and /etc/gnomish/ca/Corp-CA.crt differ only in ' +
                'letter case; expected file names keytool tells apart (it folds aliases to lower case); ' +
                'fix: rename one of them'
        lines.size() == 3
    }
}
