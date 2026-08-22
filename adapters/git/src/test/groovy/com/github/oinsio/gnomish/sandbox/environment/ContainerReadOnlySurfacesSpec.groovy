package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR20 of add-sandbox-core: injection-persistence surfaces baked into the image
 * — agent-CLI config, shell rc files, proxy/CA/build configs — are root-owned,
 * so the gnome (the image's non-root user, which the adapter never overrides to
 * root) cannot rewrite the rules of its own cage from inside the box, while its
 * own working copy stays writable.
 *
 * <p>Docker-gated (skips cleanly with no daemon). A purpose-built minimal image
 * stands in for the reference image (task 9.1): a non-root {@code gnome} user
 * owning {@code /gnomish/**} (so the adapter's clone and scratch work non-root),
 * with a root-owned {@code /etc/gnomish/agent.toml} as the control surface. This
 * proves the property at the adapter level today; the reference-image recipe
 * bakes the real surfaces later.
 *
 * <p>Implements FR20, NFR-S2 of add-sandbox-core.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerReadOnlySurfacesSpec extends Specification implements BareGitRepoFixture {

    static final String IMAGE = 'gnomish-sandbox-readonly-test:latest'
    static final String CONTROL_SURFACE = '/etc/gnomish/agent.toml'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    private final Clock clock = { -> Instant.now() } as Clock
    private ContainerTaskExecutionEnvironment env

    def setupSpec() {
        def dockerfile = """
            FROM alpine:3
            RUN apk add --no-cache git \\
             && adduser -D -u 1000 gnome \\
             && mkdir -p /gnomish/work /gnomish/scratch \\
             && chown -R gnome:gnome /gnomish \\
             && mkdir -p /etc/gnomish \\
             && printf 'baked-rules\\n' > ${CONTROL_SURFACE} \\
             && chown -R root:root /etc/gnomish \\
             && chmod 0644 ${CONTROL_SURFACE}
            USER gnome
        """.stripIndent()
        def build = new ProcessBuilder('docker', 'build', '-t', IMAGE, '-').redirectErrorStream(true).start()
        build.outputStream.withWriter('UTF-8') { it << dockerfile }
        assert build.waitFor() == 0
    }

    def setup() {
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/readonly')
        env = new ContainerTaskExecutionEnvironment(
                new DockerCli(),
                'ro-' + System.nanoTime(),
                source,
                new ContainerHarvestFetch(new GitProcessRunner(), source),
                IMAGE,
                'runc',
                LIMITS,
                false,
                clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        env.materialize('task/readonly', null)
    }

    def cleanup() {
        env?.dispose()
    }

    // No cleanupSpec image removal: the test task and PIT's coverage phase run concurrently
    // (configuration-cache parallel execution), and an rmi from one JVM races the other's
    // setupSpec-build → setup-materialize window. The tagged test image is tiny and rebuilds
    // from cache instantly, so leaving it local is the race-free choice (same as GitSandboxImage).

    private int run(String script) {
        def handle = env.exec(new ExecCommand(['sh', '-c', script], [:], null, true))
        handle.output().readAllBytes()
        handle.waitForExit()
    }

    def "FR20: the gnome can write its own working copy but not a root-owned control surface"() {
        expect: 'the gnome owns and can write the working copy'
        run('echo mine > /gnomish/work/mine.txt') == 0

        when: 'the gnome tries to rewrite the baked control surface'
        def writeExit = run("echo pwned > ${CONTROL_SURFACE}")

        then: 'the write fails and the surface is unchanged for later processes'
        writeExit != 0
        surfaceContent() == 'baked-rules'
    }

    private String surfaceContent() {
        def handle = env.exec(new ExecCommand([
            'sh',
            '-c',
            'cat ' + CONTROL_SURFACE
        ], [:], null, false))
        def out = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8)
        handle.waitForExit()
        out.trim()
    }
}
