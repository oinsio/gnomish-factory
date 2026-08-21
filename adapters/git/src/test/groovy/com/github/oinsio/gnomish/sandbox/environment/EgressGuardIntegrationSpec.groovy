package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.time.Instant
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M2 of add-sandbox-core (FR7, FR8, NFR-O1, NFR-R1): allowlist enforcement with
 * a real local guard — a genuine mitmdump container on the task's internal
 * network — proven from inside a genuine box by the production self-check:
 * direct egress fails, the guard 403s a non-allowlisted host, an allowlisted
 * destination (a local HTTP target on the bridge, standing in for WireMock)
 * passes, the isolation metadata matches; the denied probe's denial then reads
 * back as a structured finding, and a stopped guard is brought back by the
 * factory. A direct DNS query to an external resolver from inside the box is
 * proven to get no answer — port-53 egress, a known exfiltration channel, has
 * no route on the internal-only network (FR7).
 *
 * <p>Docker-gated and guard-image-gated (skips cleanly with no daemon or no
 * pullable mitmproxy image).
 */
@IgnoreIf({
    !GuardImageAvailability.available()
})
class EgressGuardIntegrationSpec extends Specification implements BareGitRepoFixture {

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
        key = 'eg-' + System.nanoTime()

        and_startAllowedTargetOnBridge()
        and_materializeBoxOnInternalNetwork()
        guard = new EgressGuard(docker, key, GuardImageAvailability.IMAGE, [targetIp], tempDir.resolve('guard-cfg'), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
    }

    def cleanup() {
        env?.dispose()
        if (targetName != null) {
            docker.run(DockerCommands.removeContainer(targetName))
        }
    }

    def "M2: the self-check passes against a real guard and the denial reads back as a finding"() {
        given: 'the production self-check over the real box, guard, and allowlist'
        def selfCheck = new EnvironmentSelfCheck(env, guard, docker, key, 'runc', [targetIp], new ThreadSleeper())

        when: 'all probes run — direct egress, denied host, allowlisted host, isolation'
        selfCheck.verify()

        then:
        noExceptionThrown()

        when: 'the denials the denied-host probe provoked are read back through the PORT type'
        // FR1 of fix-denial-report-attachment: the consumer at the round boundary holds
        // TaskExecutionEnvironment, never this adapter — the denials must arrive that way.
        TaskExecutionEnvironment port = new SelfCheckedEnvironment(env, selfCheck, guard)
        def findings = port.denialFindings()

        then: 'the blocked destination is a visible structured finding (NFR-O1, UX3)'
        findings.any {
            it.message().contains(EnvironmentSelfCheck.DENIED_PROBE_HOST)
        }

        when: 'a second round closes with no new denial in between'
        def secondRound = port.denialFindings()

        then: 'the delta cursor kept the first round\'s denial off the second attempt (D3, UX2)'
        secondRound == []
    }

    def "FR7: a direct DNS query to an external resolver gets no answer"() {
        when: 'a process in the box resolves a name against 8.8.8.8 directly, bypassing the guard'
        def handle = env.exec(new ExecCommand(
                        [
                            'timeout',
                            '5',
                            'nslookup',
                            'gnomish-dns-probe.invalid',
                            '8.8.8.8'
                        ],
                        [:],
                        null,
                        true))
        def output = new String(handle.output().readAllBytes(), 'UTF-8')
        def exit = handle.waitForExit()

        then: 'the query gets no answer — the internal-only network has no port-53 route out'
        // A resolved answer would exit 0 and print a "Name:" answer section; the
        // internal network denies the packet a route, so nslookup times out under
        // `timeout` (non-zero exit) and never reaches the answer section (FR7).
        exit != 0
        !output.contains('Name:')
    }

    def "NFR-R1: a stopped guard is brought back by ensureRunning"() {
        given: 'a running guard that then dies'
        guard.ensureRunning()
        assert docker.run(DockerCommands.stop(FactoryDockerLabels.guardName(key))).ok()

        when:
        guard.ensureRunning()

        then:
        docker.run(GuardCommands.inspectGuardRunning(key)).stdout().strip() == 'true'
    }

    /** A local HTTP target on the default bridge — the allowlisted destination the guard can reach. */
    private void and_startAllowedTargetOnBridge() {
        targetName = 'gnomish-egress-target-' + key
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
        assert docker.run([
            'inspect',
            '-f',
            '{{.State.Running}}',
            targetName
        ]).stdout().strip() == 'true':
        'target container is not running — its httpd did not start'
        def inspect = docker.run([
            'inspect',
            '-f',
            '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}',
            targetName
        ])
        assert inspect.ok()
        targetIp = inspect.stdout().strip()
        // Docker 29 renders a missing IP as the literal 'invalid IP'; require a real IPv4 address.
        assert targetIp ==~ /(\d{1,3}\.){3}\d{1,3}/: "target container has no bridge IP: '${targetIp}'"
    }

    private void and_materializeBoxOnInternalNetwork() {
        def source = initWorkingRepo(tempDir, 'factory-clone')
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', 'task/egress')
        env = new ContainerTaskExecutionEnvironment(
                docker,
                key,
                source,
                new ContainerHarvestFetch(new GitProcessRunner(), source),
                EgressProbeImage.ensureBuilt(),
                'runc',
                LIMITS,
                false,
                clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        env.materialize('task/egress', null)
    }
}
