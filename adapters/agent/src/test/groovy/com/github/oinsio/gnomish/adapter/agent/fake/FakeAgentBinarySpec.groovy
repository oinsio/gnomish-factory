package com.github.oinsio.gnomish.adapter.agent.fake

import spock.lang.Specification

/**
 * FR15, D11: the fake agent script must be resolvable from the test classpath
 * and invokable as a real subprocess (real ProcessBuilder/pipes/exit codes) —
 * this spec covers the harness mechanics themselves, since there is no
 * production CLI adapter yet to test against (task 2.1).
 */
class FakeAgentBinarySpec extends Specification {

    // D11: the script lives under a fixed test-resource path and is invoked via
    // `sh <path>`, not the bare path, matching the project's existing
    // shell-out convention (ShellCommandCheckRunner, CommandProcessRunner) —
    // Gradle's resource copy does not reliably preserve the executable bit.
    def "commandPrefix resolves an existing script and shells out through sh"() {
        when: 'the fake binary\'s command prefix is resolved'
        def prefix = FakeAgentBinary.commandPrefix()

        then: 'it is a two-element sh invocation of an existing file'
        prefix.size() == 2
        prefix[0] == 'sh'
        new File(prefix[1]).isFile()
    }

    // FR15, D11: an unknown scenario is a harness misconfiguration, not a
    // silently empty round — the fake exits 64 with a diagnostic.
    def "running with an unknown scenario fails loudly"() {
        given: 'an invocation naming a scenario that does not exist'
        def invocation = new FakeAgentInvocation(scenario: 'no-such-scenario')

        when: 'the fake binary runs'
        def result = invocation.run()

        then: 'it exits with the shell EX_USAGE convention'
        result.exitCode() == 64
    }

    // FR15, D11: missing GNOMISH_FAKE_SCENARIO is equally a harness
    // misconfiguration.
    def "running without a scenario name fails loudly"() {
        given: 'an invocation with a blank scenario'
        def invocation = new FakeAgentInvocation(scenario: '')

        when: 'the fake binary runs'
        def result = invocation.run()

        then: 'it exits with the shell EX_USAGE convention'
        result.exitCode() == 64
    }
}
