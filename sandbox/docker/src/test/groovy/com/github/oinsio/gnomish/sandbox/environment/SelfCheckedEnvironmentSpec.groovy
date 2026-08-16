package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
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
    def guard = new EgressGuard(docker, 'k1', 'mitm:12', [], java.nio.file.Path.of('/tmp/guard-k1'))

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
        environment.guard().is(guard)
    }
}
