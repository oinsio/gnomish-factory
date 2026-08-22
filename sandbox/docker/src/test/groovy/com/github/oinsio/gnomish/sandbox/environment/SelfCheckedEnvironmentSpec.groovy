package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR8, FR9 of add-sandbox-core (the integration pass): the decorator makes an
 * unmaterialized-but-unchecked box impossible — materialize always runs the
 * self-check after the delegate — and merges the guard proxy fragment into
 * every exec's factory-set env, caller values winning.
 */
class SelfCheckedEnvironmentSpec extends Specification {

    def delegate = Mock(TaskExecutionEnvironment)
    def docker = new RecordingDockerCli()
    def guard = new EgressGuard(docker, 'k1', 'mitm:12', [], Path.of('/tmp/guard-k1'), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))

    def "FR8: materialize delegates and then self-checks — a failing probe propagates and no process ran"() {
        given: 'a self-check that fails at its first probe (guard cannot come up)'
        docker.onRun = { args -> new DockerResult(1, '', 'no daemon') }
        def selfCheck = new EnvironmentSelfCheck(
                delegate, guard, docker, 'k1', 'runc', [], { d -> })
        def environment = new SelfCheckedEnvironment(delegate, selfCheck, guard)

        when:
        environment.materialize('gnomish/t', null)

        then: 'the delegate materialized first, then the check refused the environment'
        1 * delegate.materialize('gnomish/t', null)
        thrown(GuardUnavailableException)
        0 * delegate.exec(_)
    }

    def "FR9: exec merges the guard proxy fragment under the caller's factory-set variables and returns the delegate's handle"() {
        given:
        def environment = new SelfCheckedEnvironment(
                delegate, new EnvironmentSelfCheck(delegate, guard, docker, 'k1', 'runc', [], { d -> }), guard)
        def handle = Mock(ExecHandle)
        ExecCommand seen = null

        when:
        def result = environment.exec(new ExecCommand(['env'], [HTTP_PROXY: 'http://caller:1', OTHER: 'x'], null, false))

        then:
        1 * delegate.exec(_) >> { ExecCommand c -> seen = c; handle }
        result.is(handle)
        seen.env()['HTTPS_PROXY'] == guard.proxyUrl()
        seen.env()['OTHER'] == 'x'

        and: 'an explicit caller value wins over the proxy fragment'
        seen.env()['HTTP_PROXY'] == 'http://caller:1'
    }

    def "the remaining operations pass through untouched, carrying the delegate's real values"() {
        given:
        def environment = new SelfCheckedEnvironment(
                delegate, new EnvironmentSelfCheck(delegate, guard, docker, 'k1', 'runc', [], { d -> }), guard)
        def passport = CapabilityPassport.container()
        def content = 'real-bytes'.bytes

        when:
        environment.putFile('a.txt', 'x'.bytes)
        def seenContent = environment.readFile('a.txt', 10)
        environment.harvest()
        environment.dispose()
        def seenScratch = environment.scratchRoot()
        def seenPassport = environment.passport()

        then:
        1 * delegate.putFile('a.txt', _)
        1 * delegate.readFile('a.txt', 10) >> Optional.of(content)
        1 * delegate.harvest()
        1 * delegate.dispose()
        1 * delegate.scratchRoot() >> '/real/scratch'
        1 * delegate.passport() >> passport
        seenContent.isPresent()
        seenContent.get() == content
        seenScratch == '/real/scratch'
        seenPassport.is(passport)
    }

    // FR1 of fix-denial-report-attachment: the guard is reachable ONLY through the port contract —
    // a consumer holding the port type gets the denials without knowing this adapter exists
    def "FR1: denialFindings surfaces the guard's denials through the port type"() {
        given: 'a guard whose log holds one denied destination'
        docker.onRun = { List<String> args ->
            args[0] == 'logs'
            ? new DockerResult(0, '2026-08-19T10:00:00.000000000Z GNOMISH-EGRESS-DENY '
            + '{"kind":"connect","host":"evil.example.com","port":443}\n', '')
            : new DockerResult(0, '', '')
        }
        TaskExecutionEnvironment environment = new SelfCheckedEnvironment(
                delegate, new EnvironmentSelfCheck(delegate, guard, docker, 'k1', 'runc', [], { d -> }), guard)

        expect: 'the denial reads back through the port, with no downcast to the adapter type'
        environment.denialFindings()*.message() == [
            'egress denied: evil.example.com:443'
        ]
    }

    // FR5 of fix-denial-report-attachment: the cursor delimiting a round's denials reaches the
    // factory — and comes back on resume — through the port, never by naming the guard
    def "FR5: the denial cursor is read and restored through the port type"() {
        given: 'a guard container with a known identity and one denial'
        docker.onRun = { List<String> args ->
            if (args == GuardCommands.inspectGuardId('k1')) {
                return new DockerResult(0, 'sha256:container-1\n', '')
            }
            args[0] == 'logs'
                    ? new DockerResult(0, '2026-08-19T10:00:00.000000000Z GNOMISH-EGRESS-DENY '
                    + '{"kind":"connect","host":"evil.example.com","port":443}\n', '')
                    : new DockerResult(0, '', '')
        }
        TaskExecutionEnvironment environment = new SelfCheckedEnvironment(
                delegate, new EnvironmentSelfCheck(delegate, guard, docker, 'k1', 'runc', [], { d -> }), guard)

        when: 'a round reads its denials'
        environment.denialFindings()

        then: 'the position to commit is reachable through the contract'
        environment.denialCursor().orElseThrow()
                == new DenialCursor('sha256:container-1', '2026-08-19T10:00:00.000000001Z')

        when: 'a resumed lease — a fresh guard wrapper — is handed the cursor of THIS container'
        def continuingGuard = new EgressGuard(docker, 'k1', 'mitm:12', [], Path.of('/tmp/guard-k1'), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        TaskExecutionEnvironment continuing = new SelfCheckedEnvironment(
                delegate,
                new EnvironmentSelfCheck(delegate, continuingGuard, docker, 'k1', 'runc', [], { d -> }),
                continuingGuard)
        continuing.restoreDenialCursor(new DenialCursor('sha256:container-1', '2026-08-19T10:00:00.000000001Z'))
        continuing.denialFindings()

        then: 'the offer reached the guard: the read starts at the committed position'
        docker.runs.last() == [
            'logs',
            '--tail',
            '1000',
            '--timestamps',
            '--since',
            '2026-08-19T10:00:00.000000001Z',
            'gnomish-guard-k1'
        ]

        when: 'a resumed lease is instead handed a cursor from another container'
        def resumedGuard = new EgressGuard(docker, 'k1', 'mitm:12', [], Path.of('/tmp/guard-k1'), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        TaskExecutionEnvironment resumed = new SelfCheckedEnvironment(
                delegate,
                new EnvironmentSelfCheck(delegate, resumedGuard, docker, 'k1', 'runc', [], { d -> }),
                resumedGuard)
        resumed.restoreDenialCursor(new DenialCursor('sha256:container-elsewhere', '2026-08-19T11:00:00Z'))

        then: 'the offer reached the guard, which dropped it and read the live log from its start'
        resumed.denialFindings()*.message() == [
            'egress denied: evil.example.com:443'
        ]
    }

    // NFR-R1: denial observability never takes a healthy round down
    def "NFR-R1: an unreadable guard log degrades to an empty list, never a failure"() {
        given: 'the guard container is gone'
        docker.onRun = { List<String> args ->
            new DockerResult(1, '', 'No such container')
        }
        TaskExecutionEnvironment environment = new SelfCheckedEnvironment(
                delegate, new EnvironmentSelfCheck(delegate, guard, docker, 'k1', 'runc', [], { d -> }), guard)

        expect:
        environment.denialFindings() == []
    }
}
