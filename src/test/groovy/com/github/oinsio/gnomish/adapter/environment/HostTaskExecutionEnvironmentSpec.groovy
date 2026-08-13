package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.TempDir

/**
 * The host {@link TaskExecutionEnvironment} adapter (task 1.3, FR2) passes the
 * shared port contract over a real working copy, and additionally: its passport
 * honestly declares no isolation; the child environment is the layered positive
 * allowlist of D6 — the fixed host base set plus passthrough plus the command's
 * factory-set fragment, nothing inherited implicitly (task 7.1/7.2, M3); a
 * failed process start surfaces as {@link ProcessStartException}; and
 * file-channel paths escaping the roots — including via a symlink — are refused.
 *
 * <p>Implements FR2, FR4, FR9, NFR-S3, M1, M3 of add-sandbox-core.
 */
class HostTaskExecutionEnvironmentSpec extends TaskExecutionEnvironmentContract {

    @TempDir
    Path workingCopy

    private final Clock clock = { -> Instant.now() } as Clock

    @Override
    protected Optional<TaskExecutionEnvironment> arrange() {
        def e = new HostTaskExecutionEnvironment(workingCopy, clock, ChildEnvAllowlist.none())
        e.materialize('task/contract', null)
        Optional.of(e)
    }

    @Override
    protected String portName() {
        'TaskExecutionEnvironment (host)'
    }

    private HostTaskExecutionEnvironment hostEnv(ChildEnvAllowlist allowlist = ChildEnvAllowlist.none()) {
        new HostTaskExecutionEnvironment(workingCopy, clock, allowlist)
    }

    private static String readFully(InputStream stream) {
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    }

    // FR2: the host passport declares no isolation
    def "passport declares no isolation, no egress control, no task-to-task boundary"() {
        expect:
        def passport = hostEnv().passport()
        passport.isolation() == IsolationLevel.NONE
        !passport.egressControlled()
        !passport.taskToTaskBoundary()
    }

    // FR9, D6: the base set reaches the child (PATH is in essentially every factory environment)
    def "the host base set reaches the child environment"() {
        given: 'the host env with the empty allowlist'
        def e = hostEnv()

        when: 'a command echoes a base variable'
        def handle = e.exec(new ExecCommand(['sh', '-c', 'echo "[$PATH]"'], [:], null, false))
        def out = readFully(handle.output())
        handle.waitForExit()

        then: 'PATH, composed from the base set, is present'
        out.trim() != '[]'
    }

    // FR9, M3 (task 7.2): nothing outside base ∪ passthrough ∪ factory-set reaches the child —
    // the child's full env name list is a subset of the composed allowlist plus the few names the
    // shell sets for itself, whatever the factory JVM's environment holds.
    def "no factory environment variable outside the allowlist leaks into the child"() {
        given: 'the host env with the empty allowlist and one factory-set variable'
        def e = hostEnv()
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

        when: 'a command dumps every environment variable name it sees'
        def handle = e.exec(new ExecCommand(
                        [
                            'sh',
                            '-c',
                            'env | cut -d= -f1'
                        ], [GNOMISH_PROBE: 'x'], null, false))
        def names = readFully(handle.output()).readLines().findAll {
            !it.isEmpty()
        } as Set
        handle.waitForExit()

        then: 'the factory-set variable arrived, and every name is allowlisted or shell-internal'
        names.contains('GNOMISH_PROBE')
        def allowed = (HostTaskExecutionEnvironment.BASE_ENV_NAMES as Set) + ['GNOMISH_PROBE'] + shellSelfSet
        names.every { allowed.contains(it) }
    }

    // FR9, D6: a declared credential never reaches the child, while an untouched base variable
    // survives (proven against the test JVM's own always-present HOME/USER: HOME is in the base
    // set, so its absence can only come from the credential subtraction).
    def "a declared credential is excluded from the composed environment while a base variable survives"() {
        given: 'an allowlist declaring HOME a credential, both HOME and USER present in the factory environment'
        def e = hostEnv(ChildEnvAllowlist.of([], ['HOME']))

        when: 'a command reports both'
        def report = 'if [ -n "${HOME:-}" ]; then echo HOME=present; else echo HOME=absent; fi; if [ -n "${USER:-}" ]; then echo USER=present; else echo USER=absent; fi'
        def handle = e.exec(new ExecCommand(['sh', '-c', report], [:], null, false))
        def lines = readFully(handle.output()).readLines()
        handle.waitForExit()

        then: 'the declared credential is gone; the untouched base var survives'
        lines.contains('HOME=absent')
        lines.contains('USER=present')
    }

    // FR9, D6 (task 7.2): a passthrough name carries its live factory-environment value into the
    // child; the value comes from the allowlist's env source at exec time, never from config.
    def "a passthrough name carries a live value into the child"() {
        given: 'an allowlist passing GNOMISH_TOOLCHAIN through, backed by a mutable env source'
        def factoryEnv = [GNOMISH_TOOLCHAIN: 'first']
        def e = hostEnv(ChildEnvAllowlist.over(['GNOMISH_TOOLCHAIN'], [], {
            -> factoryEnv
        }))

        when: 'the factory value changes between execs'
        def report = 'echo "[$GNOMISH_TOOLCHAIN]"'
        def first = readFully(e.exec(new ExecCommand(['sh', '-c', report], [:], null, false)).output()).trim()
        factoryEnv.GNOMISH_TOOLCHAIN = 'second'
        def second = readFully(e.exec(new ExecCommand(['sh', '-c', report], [:], null, false)).output()).trim()

        then: 'each child observed the value current at its exec'
        first == '[first]'
        second == '[second]'
    }

    // FR4, NFR-R1: a process that cannot start surfaces as ProcessStartException
    def "a failed process start throws ProcessStartException"() {
        given: 'a host env'
        def e = hostEnv()

        when: 'a nonexistent binary is executed'
        e.exec(ExecCommand.of([
            'definitely-not-a-real-binary-xyzzy'
        ]))

        then:
        thrown(ProcessStartException)
    }

    // NFR-S3, FR17: a channel path escaping the roots is refused
    def "putFile to a path escaping the roots is refused"() {
        given: 'a materialized host env'
        def e = hostEnv()
        e.materialize('task/escape', null)

        when: 'the factory writes just above the working copy'
        e.putFile('../escape.txt', 'x'.getBytes(StandardCharsets.UTF_8))

        then:
        thrown(PathEscapeException)

        cleanup:
        e.dispose()
    }

    // NFR-S3: a symlink resolving outside the roots is refused on read
    def "readFile through a symlink escaping the roots is refused"() {
        given: 'a secret file outside the roots and a symlink to it inside the working copy'
        def e = hostEnv()
        e.materialize('task/symlink', null)
        def outside = workingCopy.parent.resolve('outside-secret.txt')
        Files.writeString(outside, 'top secret')
        def link = workingCopy.resolve('peek')
        Files.createSymbolicLink(link, outside)

        when: 'the factory reads through the symlink'
        e.readFile('peek', 1024)

        then:
        thrown(PathEscapeException)

        cleanup:
        e.dispose()
    }

    // FR17: a planted symlink cannot smuggle a channel write into .git/** — the resolver
    //     compares the symlink-resolved anchor against the working copy's .git as well
    def "putFile through a symlink into .git is refused"() {
        given: 'a working copy with a .git directory and a symlink pointing into it'
        def e = hostEnv()
        e.materialize('task/git-symlink', null)
        Files.createDirectories(workingCopy.resolve('.git/hooks'))
        def link = workingCopy.resolve('innocent')
        Files.createSymbolicLink(link, workingCopy.resolve('.git/hooks'))

        when: 'the factory writes through the symlink'
        e.putFile('innocent/post-checkout', 'planted'.getBytes(StandardCharsets.UTF_8))

        then:
        thrown(PathEscapeException)

        cleanup:
        e.dispose()
    }

    // NFR-R2: scratch content is removed by dispose
    def "dispose removes the scratch area"() {
        given: 'a materialized host env with a scratch file'
        def e = hostEnv()
        e.materialize('task/scratch', null)
        def scratch = Path.of(e.scratchRoot())
        e.putFile(e.scratchRoot() + '/leftover', 'x'.getBytes(StandardCharsets.UTF_8))

        when: 'dispose runs'
        e.dispose()

        then: 'the scratch directory is gone'
        !Files.exists(scratch)
    }
}
