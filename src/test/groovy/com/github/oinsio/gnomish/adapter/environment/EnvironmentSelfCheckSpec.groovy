package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.jspecify.annotations.Nullable
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, UX2, NFR-R1 of add-sandbox-core (design D5): the mandatory fail-closed
 * self-check — direct egress must fail, a non-allowlisted destination must get
 * the guard's 403, an allowlisted one must pass, and the isolation metadata
 * must match the passport; the first mismatch throws naming the probe, and a
 * dead guard is restarted before any probe runs. Daemon-free: probes run
 * against a scripted environment, isolation reads against the recording docker
 * fake.
 */
class EnvironmentSelfCheckSpec extends Specification {

    static final String ALLOWED = 'registry.example.com'

    @TempDir
    Path tempDir

    def docker = new RecordingDockerCli()
    def environment = new ScriptedEnvironment()

    /** Scripted probe answers: direct egress blocked, guard denies with 403, allowlisted reachable. */
    def probes = { List<String> argv ->
        if (argv.contains('--noproxy')) {
            return [
                7,
                'curl: (7) Failed to connect'
            ]
        }
        if (argv.any { it.contains(EnvironmentSelfCheck.DENIED_PROBE_HOST) }) {
            return [0, '403']
        }
        [0, '']
    }

    int sleeps = 0

    private EnvironmentSelfCheck selfCheck(List<String> allowlist = [ALLOWED]) {
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.inspectGuardRunning('k1')) {
                return new DockerResult(0, 'true\n', '')
            }
            if (args == GuardCommands.inspectNetworkInternal('k1')) {
                return new DockerResult(0, 'true\n', '')
            }
            if (args == GuardCommands.inspectRuntime('k1')) {
                return new DockerResult(0, 'runc\n', '')
            }
            new DockerResult(0, '', '')
        }
        environment.onExec = probes
        def guard = new EgressGuard(docker, 'k1', 'mitmproxy/mitmproxy:12', allowlist, tempDir.resolve('cfg'))
        new EnvironmentSelfCheck(environment, guard, docker, 'k1', 'runc', allowlist, { Duration d -> sleeps++ } as Sleeper)
    }

    def "FR8: a healthy environment passes all probes"() {
        when:
        selfCheck().verify()

        then:
        noExceptionThrown()

        and: 'the network probes ran inside the box via exec, proxied ones through the guard'
        environment.execs.any { it.contains('--noproxy') }
        environment.execs.any { argv -> argv.any { it.contains(EnvironmentSelfCheck.DENIED_PROBE_HOST) } }
        environment.execs.any { argv ->
            argv.contains('http://gnomish-guard:8080') && argv.any { it.contains(ALLOWED) }
        }
    }

    def "FR8: direct egress unexpectedly succeeding fails the direct-egress probe"() {
        given: 'the box can reach the world without the proxy — broken isolation'
        def check = selfCheck()
        environment.onExec = { List<String> argv ->
            argv.contains('--noproxy') ? [0, ''] : probes.call(argv)
        }

        when:
        check.verify()

        then: 'the failure names the probe (UX2)'
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'direct-egress'
    }

    def "FR8: a non-allowlisted destination not answered with the guard's 403 fails the denied-host probe"() {
        given: 'the guard answers 200 — the allowlist is not enforced'
        def check = selfCheck()
        environment.onExec = { List<String> argv ->
            argv.any { it.contains(EnvironmentSelfCheck.DENIED_PROBE_HOST) } ? [0, '200'] : probes.call(argv)
        }

        when:
        check.verify()

        then: 'a wrong HTTP status fails immediately (no readiness retry) and the observation is in the message'
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'denied-host'
        failure.message.contains('200')
        sleeps == 0
    }

    def "NFR-R1: a just-started guard not answering yet is retried before the denied probe fails"() {
        given: 'the guard refuses connections for the first two attempts, then denies properly'
        def check = selfCheck()
        def deniedAttempts = 0
        environment.onExec = { List<String> argv ->
            if (argv.any { it.contains(EnvironmentSelfCheck.DENIED_PROBE_HOST) }) {
                deniedAttempts++
                return deniedAttempts <= 2 ? [7, '000'] : [0, '403']
            }
            probes.call(argv)
        }

        when:
        check.verify()

        then: 'exactly one pause between each retried attempt, none before the first'
        noExceptionThrown()
        deniedAttempts == 3
        sleeps == 2
    }

    def "FR8: a guard that never answers exhausts the readiness retry and fails the denied-host probe"() {
        given:
        def check = selfCheck()
        def deniedAttempts = 0
        environment.onExec = { List<String> argv ->
            if (argv.any { it.contains(EnvironmentSelfCheck.DENIED_PROBE_HOST) }) {
                deniedAttempts++
                return [7, '000']
            }
            probes.call(argv)
        }

        when:
        check.verify()

        then: 'the full retry budget was spent — ten attempts, nine pauses — before failing'
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'denied-host'
        deniedAttempts == 10
        sleeps == 9
    }

    def "FR8: an unreachable allowlisted destination fails the allowlisted-host probe"() {
        given: 'the allowlisted host cannot be reached through the guard'
        def check = selfCheck()
        environment.onExec = { List<String> argv ->
            if (argv.contains('http://gnomish-guard:8080') && argv.any { it.contains(ALLOWED) }) {
                return [
                    56,
                    'curl: (56) proxy refused'
                ]
            }
            probes.call(argv)
        }

        when:
        check.verify()

        then:
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'allowlisted-host'
    }

    def "FR8: wildcard allowlist entries are never dialed as probe targets"() {
        when: 'the allowlist leads with a wildcard, followed by a concrete host'
        selfCheck(['*.maven.org', ALLOWED]).verify()

        then:
        noExceptionThrown()

        and: 'every probe URL targets the concrete host — a wildcard names no single destination'
        !environment.execs.any { argv -> argv.any { it.contains('*.') } }
        environment.execs.any { argv -> argv.contains('http://' + ALLOWED + '/') }
    }

    def "FR8: an empty allowlist skips the allowlisted-host probe and still verifies denial"() {
        when:
        selfCheck([]).verify()

        then:
        noExceptionThrown()

        and: 'the denied probe still ran; no proxied probe targeted the fallback host'
        environment.execs.any { argv -> argv.any { it.contains(EnvironmentSelfCheck.DENIED_PROBE_HOST) } }
        !environment.execs.any { argv ->
            argv.contains('http://gnomish-guard:8080') && argv.any { it.contains('example.com/') && !it.contains('invalid') }
        }
    }

    def "FR8: a task network without the internal flag fails the isolation probe"() {
        given:
        def check = selfCheck()
        def base = docker.onRun
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectNetworkInternal('k1') ? new DockerResult(0, 'false\n', '') : base.call(args)
        }

        when:
        check.verify()

        then: 'the silent-protection-degradation class is caught (D5)'
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'isolation'
    }

    def "FR8: a container under an unexpected runtime fails the isolation probe"() {
        given: 'the daemon silently fell back to another runtime'
        def check = selfCheck()
        def base = docker.onRun
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectRuntime('k1') ? new DockerResult(0, 'runsc\n', '') : base.call(args)
        }

        when:
        check.verify()

        then:
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'isolation'
    }

    def "FR8: a passport without guarded container isolation fails the isolation probe"() {
        given: 'an environment whose passport honestly declares no isolation'
        def check = selfCheck()
        environment.passport = CapabilityPassport.hostNoIsolation()

        when:
        check.verify()

        then:
        def failure = thrown(SelfCheckFailedException)
        failure.probe() == 'isolation'
    }

    def "NFR-R1: a guard that cannot be brought up surfaces as the guard's infrastructure failure"() {
        given: 'every guard state probe reports it not running and repair does nothing'
        def check = selfCheck()
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1')
            ? new DockerResult(0, 'false\n', '')
            : new DockerResult(0, '', '')
        }

        when:
        check.verify()

        then: 'no probe ran in the box'
        thrown(GuardUnavailableException)
        environment.execs.isEmpty()
    }

    /** A hand-rolled fake environment: scripted exec answers, everything else unsupported. */
    static class ScriptedEnvironment implements TaskExecutionEnvironment {

        List<List<String>> execs = []
        Closure<List> onExec = { List<String> argv -> [0, ''] }
        CapabilityPassport passport = CapabilityPassport.container()

        @Override
        ExecHandle exec(ExecCommand command) {
            execs << command.command()
            def (int code, String out) = onExec.call(command.command())
            return new ExecHandle() {

                        @Override
                        InputStream output() {
                            new ByteArrayInputStream(out.getBytes('UTF-8'))
                        }

                        @Override
                        Instant startedAt() {
                            Instant.EPOCH
                        }

                        @Override
                        ExecHandle.Wait waitForExitOrTimeout(Duration timeout, Clock clock) {
                            throw new UnsupportedOperationException('not used by the self-check')
                        }

                        @Override
                        int waitForExit() {
                            code
                        }
                    }
        }

        @Override
        void materialize(String branch, @Nullable String commitPin) {
            throw new UnsupportedOperationException()
        }

        @Override
        void putFile(String path, byte[] content) {
            throw new UnsupportedOperationException()
        }

        @Override
        Optional<byte[]> readFile(String path, long sizeCap) {
            throw new UnsupportedOperationException()
        }

        @Override
        void harvest() {
            throw new UnsupportedOperationException()
        }

        @Override
        void dispose() {
            throw new UnsupportedOperationException()
        }

        @Override
        String scratchRoot() {
            throw new UnsupportedOperationException()
        }

        @Override
        CapabilityPassport passport() {
            passport
        }
    }
}
