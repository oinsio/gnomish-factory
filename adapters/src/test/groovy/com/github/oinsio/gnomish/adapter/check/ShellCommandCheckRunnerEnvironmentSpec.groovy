package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, NFR-S1, D11 of add-claim-heartbeat; FR9, M3 of add-sandbox-core; FR5 of
 * migrate-verify-to-executor-contract: a shell check's process environment is the layered
 * allowlist and nothing else. Each feature observes it through the synthetic result's output tail
 * of a command that prints what it sees and exits 1 — the runner exposes no environment otherwise.
 */
class ShellCommandCheckRunnerEnvironmentSpec extends Specification implements ShellCommandCheckRunnerTestSupport {

    @TempDir
    Path tempDir

    def runner = new ShellCommandCheckRunner(new VirtualClock())

    /** The output tail the synthetic result of a red command carries, line by line. */
    private List<String> tailOf(ShellCommandCheckRunner runner, String line) {
        findingsOf(runner.probe(command(line + '; exit 1'), workspace()))[0].details().readLines()
    }

    // Proven against real, always-present base vars (HOME/USER): HOME is in the host base set, so its
    // absence can only come from the credential exclusion.
    // Scenario: verification/verdict-executors / No factory variable leaks into a shell check
    def "FR11: withChildEnv excludes the declared credential var from a check's environment"() {
        given: 'an allowlist declaring HOME a credential'
        def credentialAwareRunner = runner.withChildEnv(TestChildEnvironment.hostAllowlist([], ['HOME']))

        when:
        def lines = tailOf(credentialAwareRunner, 'if [ -n "${HOME:-}" ]; then echo HOME=present; else echo HOME=absent; fi; '
                + 'if [ -n "${USER:-}" ]; then echo USER=present; else echo USER=absent; fi')

        then: 'the credential name is absent, an untouched base name still reaches the check'
        lines.contains('HOME=absent')
        lines.contains('USER=present')
    }

    // FR9 of add-sandbox-core: the base set reaches a check with no tracker and no passthrough.
    //     NFR-S3 of tighten-spec-contract: its values resolve through the run's allowlist (the
    //     secrets port, in production; the test JVM's environment here), which the root always binds
    def "FR9: with no tracker configured a base variable still reaches the check"() {
        expect:
        tailOf(runner.withChildEnv(TestChildEnvironment.hostAllowlist()),
                'if [ -n "${HOME:-}" ]; then echo HOME=present; else echo HOME=absent; fi').contains('HOME=present')
    }

    // FR9, M3 of add-sandbox-core (task 7.2): a check's process environment holds ONLY the
    // allowlisted variables — whatever the factory JVM environment holds, nothing outside the host
    // base set plus the factory-set findings variable (plus the shell's own names) leaks in.
    // Scenario: sandbox/execution-environment / Typical host project needs no env configuration
    // Scenario: verification/verdict-executors / No factory variable leaks into a shell check
    // Scenario: verification/verdict-executors / A shell check inherits no factory variable implicitly
    def "M3: no factory environment variable outside the allowlist leaks into a check"() {
        given:
        def shellSelfSet = [
            'PWD',
            'OLDPWD',
            'SHLVL',
            '_',
            'IFS',
            'OPTIND',
            'PS1',
            'PS2',
            'PS4',
            'PPID'
        ] as Set

        when:
        // The root's allowlist shape (no passthrough, no credential) over the test JVM's real
        // environment, so the base set is really consulted (NFR-S3 of tighten-spec-contract).
        def names = tailOf(runner.withChildEnv(TestChildEnvironment.hostAllowlist()), 'env | cut -d= -f1')
                .findAll { !it.isEmpty() } as Set

        then: 'every observed name is allowlisted or shell-internal'
        def allowed = (HostTaskExecutionEnvironment.BASE_ENV_NAMES as Set) + ['GNOMISH_FINDINGS_FILE'] + shellSelfSet
        names.every { allowed.contains(it) }
    }
}
