package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.port.Clock
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
 * The behavioral proof of the "Gradle build flows through the guard" scenario
 * (FR7, UX4 of add-sandbox-core): a JVM inside the box — which ignores the
 * {@code HTTP_PROXY} environment variable — still routes its HTTP traffic
 * through the guard, because the reference image bakes the proxy as JVM system
 * properties ({@code -Dhttp.proxyHost}), not as an env var. The image under test
 * ({@link JvmProxyEgressImage}) carries exactly that plumbing and deliberately
 * sets no {@code HTTP_PROXY}, so a request that reaches the guard could only
 * have been routed by the system property.
 *
 * <p>Two assertions together prove the traffic transits the guard: an
 * allowlisted registry (a local HTTP target on the bridge, standing in for
 * Maven Central) is reached with a 200, and a non-allowlisted host comes back
 * with the guard's own 403 — a JVM on a direct route would have gotten neither.
 *
 * <p>Docker- and guard-image-gated; builds a JDK image on demand.
 */
@IgnoreIf({
    !GuardImageAvailability.available()
})
class JvmProxyEgressE2ESpec extends Specification implements BareGitRepoFixture {

    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    private final Clock clock = { -> Instant.now() } as Clock
    private final DockerCli docker = new DockerCli()

    private String key
    private String targetName
    private String targetIp
    private ContainerTaskExecutionEnvironment env
    private EgressGuard guard

    def setup() {
        key = 'jvmproxy-' + System.nanoTime()
        and_startAllowedTargetOnBridge()
        and_materializeBoxOnInternalNetwork()
        guard = new EgressGuard(docker, key, GuardImageAvailability.IMAGE, [targetIp], tempDir.resolve('guard-cfg'), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        guard.ensureRunning()
    }

    def cleanup() {
        env?.dispose()
        if (guard != null) {
            docker.run(GuardCommands.removeGuard(key))
        }
        if (targetName != null) {
            docker.run(DockerCommands.removeContainer(targetName))
        }
    }

    def "FR7: a JVM ignoring proxy env vars still reaches an allowlisted host through the guard"() {
        when: 'the box JVM GETs the allowlisted target — routed only by the baked -Dhttp.proxyHost'
        def out = probe("http://${targetIp}/")

        then: 'it reaches the allowlisted registry through the guard'
        out.contains('HTTP 200')
    }

    def "FR7: the same JVM is denied a non-allowlisted host by the guard's own 403"() {
        when: 'the box JVM GETs a non-allowlisted host'
        def out = probe("http://${EnvironmentSelfCheck.DENIED_PROBE_HOST}/")

        then: 'the guard denies it — proof the JVM traffic transits the guard, not a direct route'
        out.contains('HTTP 403')
    }

    /**
     * Runs the baked single-file Java HTTP probe against {@code url} and returns
     * its merged output. The just-created guard may not be listening yet, so the
     * probe retries while the proxy connection is refused (no {@code "HTTP "}
     * status line) — the same readiness budget the production self-check uses.
     */
    private String probe(String url) {
        String out = ''
        for (int attempt = 0; attempt < 20; attempt++) {
            if (attempt> 0) {
                Thread.sleep(500)
            }
            def handle = env.exec(new ExecCommand(
                            [
                                'java',
                                '/gnomish-probe/Probe.java',
                                url
                            ], [:], null, true))
            out = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8)
            handle.waitForExit()
            if (out.contains('HTTP ')) {
                return out
            }
        }
        out
    }

    /** A local HTTP target on the default bridge — the allowlisted destination the guard can reach. */
    private void and_startAllowedTargetOnBridge() {
        targetName = 'gnomish-jvm-target-' + key
        def run = docker.run([
            'run',
            '-d',
            '--name',
            targetName,
            '--user',
            '0',
            EgressProbeImage.ensureBuilt(),
            'sh',
            '-c',
            'echo ok > /tmp/index.html && exec httpd -f -p 80 -h /tmp'
        ])
        assert run.ok(): "target container failed to start: ${run.stderr()}"
        def inspect = docker.run([
            'inspect',
            '-f',
            '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',
            targetName
        ])
        assert inspect.ok()
        targetIp = inspect.stdout().strip()
        assert targetIp ==~ /(\d{1,3}\.){3}\d{1,3}/: "target container has no bridge IP: '${targetIp}'"
    }

    private void and_materializeBoxOnInternalNetwork() {
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/jvm-proxy')
        env = new ContainerTaskExecutionEnvironment(
                docker,
                key,
                source,
                new ContainerHarvestFetch(new GitProcessRunner(), source),
                JvmProxyEgressImage.ensureBuilt(),
                'runc',
                LIMITS,
                false,
                clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        env.materialize('task/jvm-proxy', null)
    }
}
