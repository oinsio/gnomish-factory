package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Requires
import spock.lang.Specification

/**
 * Design D14 of fix-terminal-receipt-convergence (D5 row {@code TestDockerEnvironment}, M10): the
 * effect of the build-logic owner in the real test JVM, whichever machine runs it. A JVM cannot set
 * its own environment, so what this one observes is exactly what {@code test-conventions} handed
 * it: a client configuration of the build's own, never the operator's — so neither Testcontainers
 * nor a {@code docker} process a fixture starts runs a credential helper, a plugin hook or a proxy —
 * and the operator's endpoint, carried as {@code DOCKER_HOST} instead of a context.
 *
 * <p>The composition's branches are pinned on inner builds in {@code build-logic}
 * ({@code TestDockerEnvironmentFunctionalSpec}, {@code TestDockerConventionsFunctionalSpec}); this
 * spec pins that the shipped conventions really reach this module's test JVM.
 *
 * <p>FR13, FR14, UX4, M10 of fix-terminal-receipt-convergence.
 */
class TestDockerEnvironmentSpec extends Specification {

    /** The keys that would make a {@code docker} process or Testcontainers call out on the operator's behalf. */
    static final List<String> CALLING_OUT = [
        'credsStore',
        'credHelpers',
        'proxies',
        'plugins',
        'hooks',
        'currentContext'
    ]

    // FR13, UX4: the test JVM's DOCKER_CONFIG is the build's per-task copy, which names nothing that calls out
    // Scenario: build-system/quality-gates / The operator's credential helper is not consulted
    def "FR13, UX4: the test JVM's DOCKER_CONFIG names the build's own configuration, which calls nothing out"() {
        given:
        String dir = System.getenv('DOCKER_CONFIG')

        expect: 'a per-task copy in the build directory, not the operator\'s'
        dir != null
        Path.of(dir) != Path.of(System.getProperty('user.home'), '.docker')
        Path.of(dir).toAbsolutePath().normalize().toString().contains("${File.separator}build${File.separator}tmp${File.separator}")

        and: 'its configuration names no credential helper, proxy, plugin hook or context'
        String config = Files.readString(Path.of(dir, 'config.json'))
        CALLING_OUT.every { !config.contains("\"${it}\"") }
    }

    // FR14: no context reaches the test JVM; the endpoint travels as DOCKER_HOST
    def "FR14: the test JVM observes no DOCKER_CONTEXT"() {
        expect:
        System.getenv('DOCKER_CONTEXT') == null
    }

    // FR14: whenever Testcontainers finds a daemon, the endpoint it found was handed on as DOCKER_HOST
    // Scenario: build-system/quality-gates / Without DOCKER_HOST the current context's endpoint is handed on
    @Requires({ GiteaAvailability.dockerAvailable() })
    def "FR14: with a daemon available the test JVM carries the endpoint as DOCKER_HOST"() {
        expect:
        System.getenv('DOCKER_HOST') != null
    }

    // FR14: on macOS, Testcontainers mounts the daemon's own socket path into Ryuk, not the host's
    // Scenario: build-system/quality-gates / On macOS Testcontainers mounts the daemon's own socket path
    @Requires({
        os.macOs && System.getenv('DOCKER_HOST')?.startsWith('unix://')
    })
    def "FR14: on macOS with a unix:// endpoint the socket override names the VM's socket"() {
        expect:
        System.getenv('TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE') == '/var/run/docker.sock'
    }
}
