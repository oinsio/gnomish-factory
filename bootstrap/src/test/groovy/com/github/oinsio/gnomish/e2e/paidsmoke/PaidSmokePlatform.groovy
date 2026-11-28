package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import groovy.transform.CompileStatic
import java.nio.file.Path
import java.time.Duration
import java.time.InstantSource

/**
 * Where a row of a paid smoke spec that measures a platform fact runs the real CLI — {@link
 * PaidSmokeLivenessSpec} (FR17 of extract-agent-round-runner, design D6 "A declared liveness is
 * tested, per dialect") and {@link PaidSmokeStructuredAnswerSpec} (FR22, design D15 "A platform fact
 * to measure"): on the host the task runs on (macOS or Linux), or in a Linux container of the image
 * the operator names with {@code -PpaidSmoke.linuxImage=<image>}, an image that carries the CLI.
 *
 * <p>The container runs outside a factory network, so the image's proxy variables are unset
 * ({@code env -u}); a credential reaches it by variable name only ({@code -e NAME}), its value taken
 * by the docker client from its own environment, never from the argv.
 *
 * <p>Statically compiled for the reason {@link PaidSmokeAgentLauncher} states.
 *
 * <p>Implements FR17, FR22 of extract-agent-round-runner.
 */
@CompileStatic
enum PaidSmokePlatform {

    HOST,
    LINUX_CONTAINER

    /** The property naming the container image, forwarded by {@code paidSmokeTest}. */
    static final String IMAGE_PROPERTY = 'paidSmoke.linuxImage'

    /** The proxy variables an image may set, unset in the container. */
    static final List<String> PROXY_VARIABLES = [
        'HTTP_PROXY',
        'HTTPS_PROXY',
        'ALL_PROXY',
        'NO_PROXY',
        'http_proxy',
        'https_proxy',
        'all_proxy',
        'no_proxy'
    ].asImmutable()

    /** The binary the dialect renders: the operator's on the host, the image's own in the container. */
    String binary(String hostBinary) {
        this == HOST ? hostBinary : 'claude'
    }

    /** A label naming the platform, for the failure message. */
    String label() {
        this == HOST ? "host (${System.getProperty('os.name')})" : "Linux container (${image()})"
    }

    /**
     * The command that runs {@code argv} on this platform.
     *
     * @param argv the argv the dialect rendered, binary first
     * @param credentialNames the variable names the dialect's describe declares as credentials
     */
    List<String> command(List<String> argv, Collection<String> credentialNames) {
        if (this == HOST) {
            return argv
        }
        List<String> command = [
            'docker',
            'run',
            '--rm',
            '-i',
            '-w',
            '/tmp'
        ]
        credentialNames.each { String name -> command.addAll(['-e', name]) }
        command.addAll([
            '--entrypoint',
            'env',
            requiredImage()
        ])
        PROXY_VARIABLES.each { String name -> command.addAll(['-u', name]) }
        command.addAll(argv)
        command
    }

    /** The command that asks this platform's CLI for its version. */
    List<String> versionCommand(String hostBinary) {
        this == HOST ? [hostBinary, '--version'] : [
            'docker',
            'run',
            '--rm',
            '--entrypoint',
            'claude',
            requiredImage(),
            '--version'
        ]
    }

    /**
     * Fails fast, with the preflight's reason, when the host's CLI is not logged in; a container row
     * takes its credential by variable name and has nothing to check here.
     *
     * @param hostBinary the operator's CLI
     * @param scratch a directory the preflight may write in
     */
    void requireLogin(String hostBinary, Path scratch) {
        if (this == HOST) {
            ClaudeLoginPreflight.Result preflight = ClaudeLoginPreflight.check(hostBinary, scratch)
            assert preflight.loggedIn : "paidSmokeTest: claude CLI preflight failed — ${preflight.reason}"
        }
    }

    /**
     * The version this platform's CLI reports, its first stdout line.
     *
     * @param hostBinary the operator's CLI, used on the host
     */
    String version(String hostBinary, Path workspace, InstantSource clock, ChildEnvAllowlist allowlist) {
        List<String> command = versionCommand(hostBinary)
        def handle = PaidSmokeAgentLauncher.launch(command, workspace, clock, '', allowlist)
        List<StampedStdout.Line> lines = StampedStdout.read(handle, clock).lines
        handle.waitForExitOrTimeout(Duration.ofMinutes(2), clock)
        assert !lines.isEmpty() : "'${command.join(' ')}' printed no version"
        lines.first().text.trim()
    }

    /** The docker client's own variables the test JVM carries, so a container row reaches its daemon. */
    static List<String> dockerClientNames() {
        TestChildEnvironment.NAMES.findAll { String name ->
            name.startsWith('DOCKER_')
        }
    }

    /**
     * The image a container row runs in.
     *
     * @throws IllegalStateException naming the property when no image is named — a container row
     *     fails, never skips
     */
    static String requiredImage() {
        String image = image()
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("the Linux container row needs an image that carries the CLI: "
            + "run paidSmokeTest with -P${IMAGE_PROPERTY}=<image>")
        }
        image
    }

    private static String image() {
        System.getProperty(IMAGE_PROPERTY)
    }
}
