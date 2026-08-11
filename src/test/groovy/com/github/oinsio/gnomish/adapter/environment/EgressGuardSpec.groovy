package com.github.oinsio.gnomish.adapter.environment

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, NFR-O1, NFR-R1 of add-sandbox-core (design D4): the guard lifecycle —
 * created on the task network with a bridge leg when missing, restarted when
 * stopped, recreated once when broken, {@link GuardUnavailableException} (an
 * infrastructure failure) when nothing brings it up — plus the denial-findings
 * read and the proxy env fragment. Daemon-free against the recording docker
 * fake.
 */
class EgressGuardSpec extends Specification {

    @TempDir
    Path tempDir

    def docker = new RecordingDockerCli()

    private EgressGuard guard(List<String> allowlist = ['registry.example.com']) {
        new EgressGuard(docker, 'k1', 'mitmproxy/mitmproxy:12', allowlist, tempDir.resolve('guard-cfg'))
    }

    private static DockerResult ok(String stdout = '') {
        new DockerResult(0, stdout, '')
    }

    private static DockerResult failed(String stderr = 'boom') {
        new DockerResult(1, '', stderr)
    }

    def "FR7: a missing guard is created on the task network and connected to the bridge"() {
        given: 'no guard container exists, and every create step succeeds'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1') && !docker.runs.contains(GuardCommands.runGuard(
            'k1', 'mitmproxy/mitmproxy:12', tempDir.resolve('guard-cfg').toAbsolutePath().toString()))
            ? failed('No such object')
            : ok('true\n')
        }

        when:
        guard().ensureRunning()

        then: 'the guard is run with the rendered config and given its bridge leg'
        docker.runs.contains(GuardCommands.runGuard(
                'k1', 'mitmproxy/mitmproxy:12', tempDir.resolve('guard-cfg').toAbsolutePath().toString()))
        docker.runs.contains(GuardCommands.connectBridge('k1'))

        and: 'the first create sufficed — the recreate repair path never ran'
        !docker.runs.contains(GuardCommands.removeGuard('k1'))

        and: 'the config was rendered before the container started'
        Files.exists(tempDir.resolve('guard-cfg').resolve('guard.py'))
        Files.exists(tempDir.resolve('guard-cfg').resolve('allowlist.json'))
    }

    def "FR7: a running guard is left alone"() {
        given:
        docker.onRun = { List<String> args -> ok('true\n') }

        when:
        guard().ensureRunning()

        then: 'only the state probe ran — no run, start, or remove'
        docker.runs == [
            GuardCommands.inspectGuardRunning('k1')
        ]
    }

    def "NFR-R1: a stopped guard is restarted in place"() {
        given: 'the guard container exists but is stopped, and start brings it up'
        def started = false
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.startGuard('k1')) {
                started = true
                return ok()
            }
            args == GuardCommands.inspectGuardRunning('k1') ? ok(started ? 'true\n' : 'false\n') : ok()
        }

        when:
        guard().ensureRunning()

        then:
        docker.runs.contains(GuardCommands.startGuard('k1'))

        and: 'no recreate was needed'
        !docker.runs.any { it[0] == 'run' }
    }

    def "NFR-R1: a guard that will not start is recreated once"() {
        given: 'the guard exists, start does nothing, and only the recreated container runs'
        def recreated = false
        docker.onRun = { List<String> args ->
            if (args[0] == 'run') {
                recreated = true
                return ok()
            }
            args == GuardCommands.inspectGuardRunning('k1') ? ok(recreated ? 'true\n' : 'false\n') : ok()
        }

        when:
        guard().ensureRunning()

        then: 'the broken guard was removed and a fresh one created with its bridge leg'
        docker.runs.contains(GuardCommands.removeGuard('k1'))
        docker.runs.contains(GuardCommands.connectBridge('k1'))
    }

    def "NFR-R1: a guard nothing can bring up is an infrastructure failure"() {
        given: 'the guard is never running, whatever is tried'
        docker.onRun = { List<String> args ->
            args == GuardCommands.inspectGuardRunning('k1') ? ok('false\n') : ok()
        }

        when:
        guard().ensureRunning()

        then:
        thrown(GuardUnavailableException)
    }

    def "NFR-R1: a failing docker run of the guard is an infrastructure failure"() {
        given:
        docker.onRun = { List<String> args ->
            args[0] == 'run' ? failed('image not found') : failed('No such object')
        }

        when:
        guard().ensureRunning()

        then:
        def failure = thrown(GuardUnavailableException)
        failure.message.contains('image not found')
    }

    def "FR7: an already-connected bridge leg is not an error"() {
        given: 'run succeeds and the bridge connect reports the endpoint already exists'
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.connectBridge('k1')) {
                return failed('endpoint with name gnomish-guard-k1 already exists in network bridge')
            }
            if (args == GuardCommands.inspectGuardRunning('k1')) {
                def probes = docker.runs.count { it == GuardCommands.inspectGuardRunning('k1') }
                return probes > 1 ? ok('true\n') : failed('No such object')
            }
            ok()
        }

        when:
        guard().ensureRunning()

        then:
        noExceptionThrown()
    }

    def "NFR-O1: denial findings are parsed from a bounded guard log tail"() {
        given:
        docker.onRun = { List<String> args ->
            args == GuardCommands.guardLogs('k1', 1000)
            ? ok('GNOMISH-EGRESS-DENY {"kind":"connect","host":"evil.example.com","port":443}\n')
            : ok()
        }

        when:
        def findings = guard().denialFindings()

        then:
        findings*.message() == [
            'egress denied: evil.example.com:443'
        ]
    }

    def "NFR-O1: an unreadable guard log yields no findings, never a failure"() {
        given: 'the guard container is gone'
        docker.onRun = { List<String> args -> failed('No such container') }

        expect:
        guard().denialFindings() == []
    }

    def "FR9: the proxy env fragment names the guard by its stable network alias in both spellings"() {
        expect: 'the alias, not the per-task container name — the address baked image configs dial (9.1, D7)'
        guard().proxyUrl() == 'http://gnomish-guard:8080'
        guard().proxyEnvironment() == [
            HTTP_PROXY : 'http://gnomish-guard:8080',
            HTTPS_PROXY: 'http://gnomish-guard:8080',
            http_proxy : 'http://gnomish-guard:8080',
            https_proxy: 'http://gnomish-guard:8080',
        ]
    }
}
