package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, NFR-S2 of add-sandbox-base-image (design D14), beside the self-check suite of {@link
 * EgressGuardIntegrationSpec}: an operator layer may bake a {@code ~/.curlrc} into the gnome's
 * root-owned home (the gnome itself can no longer write one), and the self-check's probes must not
 * read it. The image here bakes one that sends every request around any proxy ({@code noproxy =
 * "*"}): read, it would turn the denied-host probe's 403 from the guard into a failed direct
 * connection and fail the self-check. The spec first shows the file is in force for a plain
 * {@code curl}, then runs the production self-check against a real guard and expects it to pass.
 *
 * <p>Docker-gated and guard-image-gated, as the suite beside it. The allowlist is empty, so the
 * allowlisted-host probe is skipped and no target container is needed.
 */
@IgnoreIf({
    !GuardImageAvailability.available()
})
class EgressSelfCheckCurlrcSpec extends Specification implements BareGitRepoFixture {

    static final String IMAGE = 'gnomish-sandbox-curlrc-test:latest'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    /** The guard-readiness pause, waited for real: the guard in the real container needs the wall time. */
    private static final Sleeper REAL_PAUSE = { Duration d ->
        Thread.sleep(d)
    } as Sleeper

    @TempDir
    Path tempDir

    private final DockerCli docker = new DockerCli()
    private ContainerTaskExecutionEnvironment env

    def setupSpec() {
        DockerImageBuilder.build(IMAGE, '''
            FROM ${BASE}
            USER root
            RUN printf 'noproxy = "*"\\n' > /home/gnome/.curlrc \\
             && chmod 0644 /home/gnome/.curlrc \\
             && gnomish-verify-image
            USER gnome
        ''')
    }

    def cleanup() {
        env?.dispose()
    }

    def "D14: a curlrc baked into the image changes no self-check verdict"() {
        given: 'a box on the image, behind a real guard with an empty allowlist'
        def key = 'curlrc-' + System.nanoTime()
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/curlrc')
        env = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(docker, IMAGE, LIMITS, new VirtualClock()),
                key, new BoxGitLink(source, new ContainerHarvestFetch(new GitProcessRunner(), source)), ChildEnvAllowlist.none(), 'proj-1')
        env.materialize('task/curlrc', null)
        def guard = new EgressGuard(docker, key, GuardImageAvailability.IMAGE, [], tempDir.resolve('guard-cfg'),
        new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        guard.ensureRunning()

        expect: 'the curlrc is in force: a plain curl ignores the guard named by -x and resolves the host itself'
        def plain = plainDeniedProbe(guard)
        plain.contains("Could not resolve host: ${EnvironmentSelfCheck.DENIED_PROBE_HOST}")
        !plain.endsWith('403')

        when: 'the production self-check runs its probes in the same box'
        new EnvironmentSelfCheck(env, guard, docker, key, 'runc', [], REAL_PAUSE).verify()

        then: 'the verdict is the one without the curlrc'
        noExceptionThrown()
    }

    /** The denied-host probe's request without {@code -q}, its stderr merged: what curl does when it reads the curlrc. */
    private String plainDeniedProbe(EgressGuard guard) {
        def handle = env.exec(ExecCommand.of([
            'curl',
            '-sS',
            '-o',
            '/dev/null',
            '--max-time',
            '10',
            '-w',
            '%{http_code}',
            '-x',
            guard.proxyUrl(),
            "http://${EnvironmentSelfCheck.DENIED_PROBE_HOST}/".toString()
        ]))
        def out = new String(handle.output().stream().readAllBytes(), StandardCharsets.UTF_8)
        handle.waitForExit()
        out.strip()
    }
}
