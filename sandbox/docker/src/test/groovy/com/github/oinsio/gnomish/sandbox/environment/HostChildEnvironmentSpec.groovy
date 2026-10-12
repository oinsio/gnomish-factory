package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, D6 of add-sandbox-core: the child environment a host {@code exec} composes, observed from
 * inside the real child process — the fixed documented base set and nothing an operator's shell
 * adds beside it (an agent socket, a cloud key), and the debug line naming the applied names with
 * no value in it. The factory environment is a planted map ({@link ChildEnvAllowlist#over}), so
 * the spec depends on no variable of the JVM running it.
 */
class HostChildEnvironmentSpec extends Specification {

    /** The minimum the scenario documents: PATH, HOME, TMPDIR, locale variables, TERM, USER, SHELL. */
    static final List<String> DOCUMENTED_MINIMUM =
    [
        'PATH',
        'HOME',
        'TMPDIR',
        'LANG',
        'LC_ALL',
        'LC_CTYPE',
        'TERM',
        'USER',
        'SHELL'
    ]

    /** Names a POSIX shell sets for itself, whatever environment it was started with. */
    static final Set<String> SHELL_SELF_SET = ['PWD', 'OLDPWD', 'SHLVL', '_'] as Set

    @TempDir
    Path workingCopy

    /** A factory environment carrying every base name, an agent socket and an unrelated secret. */
    private static Map<String, String> operatorShell() {
        def env = DOCUMENTED_MINIMUM.collectEntries {
            [(it): "value-of-${it}".toString()]
        }
        env.PATH = '/usr/bin:/bin'
        env.SSH_AUTH_SOCK = '/tmp/agent.sock'
        env.AWS_SECRET_ACCESS_KEY = 'cloud-secret-value'
        env
    }

    private Set<String> childNames(HostTaskExecutionEnvironment env) {
        def handle = env.exec(new ExecCommand([
            'sh',
            '-c',
            'env | cut -d= -f1'
        ], [:], null, false))
        def names = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8).readLines()
        handle.waitForExit()
        (names.findAll { !it.isEmpty() } as Set) - SHELL_SELF_SET
    }

    // Scenario: sandbox/execution-environment / The host base set is a fixed minimum without agent sockets
    def "FR9: a host child receives exactly the documented base set, and SSH_AUTH_SOCK is absent"() {
        given: 'a factory environment holding the base names, SSH_AUTH_SOCK and a cloud key; no passthrough'
        def env = new HostTaskExecutionEnvironment(workingCopy, new VirtualClock(),
                ChildEnvAllowlist.of([], [], TestChildEnvironment.valuesOf(operatorShell())))

        expect: 'the adapter’s base set is the documented minimum'
        HostTaskExecutionEnvironment.BASE_ENV_NAMES == DOCUMENTED_MINIMUM

        when: 'a host-bound process lists the variables it sees'
        def names = childNames(env)

        then: 'exactly the base set — no agent socket, no unrelated cloud key'
        names == DOCUMENTED_MINIMUM as Set
        !names.contains('SSH_AUTH_SOCK')
        !names.contains('AWS_SECRET_ACCESS_KEY')
    }

    // Scenario: sandbox/execution-environment / Applied allowlist names are logged without values
    def "FR9, UX6: each exec logs the applied allowlist names at debug, and no value"() {
        given: 'a passthrough name and a factory-set variable beside the base set'
        def shell = operatorShell() + [JAVA_HOME: '/opt/jdk-secret-path']
        def env = new HostTaskExecutionEnvironment(workingCopy, new VirtualClock(),
                ChildEnvAllowlist.of(['JAVA_HOME'], [], TestChildEnvironment.valuesOf(shell)))

        when: 'one process is executed'
        def events = LogCaptureSupport.capture(ChildEnvAllowlist, Level.DEBUG) {
            def handle = env.exec(new ExecCommand(['true'], [GNOMISH_FINDINGS_FILE: '/scratch/findings.json'], null, false))
            handle.waitForExit()
        }

        then: 'one debug line for that exec names the applied names'
        events.size() == 1
        def line = events[0]
        line.level == Level.DEBUG
        (DOCUMENTED_MINIMUM + [
            'JAVA_HOME',
            'GNOMISH_FINDINGS_FILE'
        ]).every {
            line.formattedMessage.contains(it)
        }

        and: 'no value of any layer appears in it'
        def values = shell.values() + ['/scratch/findings.json']
        values.every { !line.formattedMessage.contains(it) }
    }
}
