package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist
import com.github.oinsio.gnomish.adapter.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, FR8, NFR-R2, NFR-S1, D6 of add-manual-run: the real command runner classifies a
 * completed command run: exit 0 &rarr; Pass (a findings file is ignored with a warning); exit
 * 126/127 &rarr; CannotVerify (shell convention for not-executable / not-found, the findings
 * file plays no role); any other non-zero exit &rarr; Fail with either the structured findings
 * the command wrote, or one synthetic finding carrying the output tail if none were written or
 * they were malformed. Process-spawning mechanics (cwd, environment, output tail bounding) are
 * covered by {@code CommandProcessRunnerSpec}.
 */
class ShellCommandCheckRunnerSpec extends Specification {

    @TempDir
    Path tempDir

    def runner = new ShellCommandCheckRunner()

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    private static VerifyCheck.Command command(String line) {
        new VerifyCheck.Command(line)
    }

    private static List<ILoggingEvent> capture(Class<?> loggerOwner, Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(loggerOwner)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    def "run(...) maps exit 0 to a Pass verdict"() {
        given:
        def check = command('exit 0')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Pass
    }

    def "run(...) maps a non-zero exit other than 126/127 to Fail with one synthetic finding carrying the output tail"() {
        given: 'a command that exits 1 and writes a distinctive tail without a findings file'
        def check = command('echo boom-output; exit 1')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].message().contains('1')
        fail.findings()[0].details().contains('boom-output')
    }

    def "run(...) maps exit 126 to CannotVerify"() {
        given:
        def check = command('exit 126')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        def cannotVerify = verdict as Verdict.CannotVerify
        cannotVerify.reason().contains('126')
    }

    def "run(...) maps exit 127 to CannotVerify"() {
        given:
        def check = command('exit 127')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
        def cannotVerify = verdict as Verdict.CannotVerify
        cannotVerify.reason().contains('127')
    }

    def "a workspace that is not a DirectoryWorkspace yields CannotVerify"() {
        given:
        def check = command('pwd')
        def opaqueWorkspace = new Workspace() {}

        when:
        def verdict = runner.run(check, opaqueWorkspace)

        then:
        verdict instanceof Verdict.CannotVerify
    }

    def "a shell start failure is caught and yields CannotVerify"() {
        given: 'a runner configured with a nonexistent shell executable'
        def failingRunner = new ShellCommandCheckRunner('/no/such/shell-binary')
        def check = command('pwd')

        when:
        def verdict = failingRunner.run(check, workspace())

        then:
        verdict instanceof Verdict.CannotVerify
    }

    def "GNOMISH_FINDINGS_FILE is passed as a path outside the workspace root"() {
        given: 'a command that echoes the env var so the test can inspect its value, run through run() so the runner creates the temp path itself'
        def check = command('echo $GNOMISH_FINDINGS_FILE; exit 1')

        when:
        def verdict = runner.run(check, workspace())

        then: 'the synthetic finding carries the output tail, which contains the echoed path'
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        def path = fail.findings()[0].details().trim()
        !path.isEmpty()
        !path.startsWith(tempDir.toRealPath().toString())
    }

    def "structured findings win: exit 1 with two valid findings written replaces the synthetic finding"() {
        given: 'a command that exits 1 and writes two valid findings to the findings file'
        def check = command('''cat > "$GNOMISH_FINDINGS_FILE" <<'EOF'
{"findings":[{"message":"first problem","location":"a.txt:1"},{"message":"second problem","details":"more info"}]}
EOF
exit 1''')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 2
        fail.findings()[0].message() == 'first problem'
        fail.findings()[0].location() == 'a.txt:1'
        fail.findings()[1].message() == 'second problem'
        fail.findings()[1].details() == 'more info'
    }

    def "broken reporter cannot mask a red check: malformed findings file degrades to the synthetic finding, verdict stays Fail"() {
        given: 'a command that exits 1 and writes unparseable JSON to the findings file'
        def check = command('echo boom-output; echo "not valid json {{{" > "$GNOMISH_FINDINGS_FILE"; exit 1')

        when:
        def verdict = runner.run(check, workspace())

        then: 'the verdict is Fail, not CannotVerify - a broken reporter never masks a red check'
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].message().contains('1')
        fail.findings()[0].details().contains('boom-output')
    }

    def "valid JSON missing the findings array is malformed and degrades to the synthetic finding"() {
        given: 'a command that exits 1, echoes a distinctive tail, and writes well-formed JSON with no findings key'
        def check = command('''echo boom-output; cat > "$GNOMISH_FINDINGS_FILE" <<'EOF'
{"unrelated":true}
EOF
exit 1''')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].message().contains('1')
        fail.findings()[0].details().contains('boom-output')
    }

    def "a findings entry with a blank message is malformed and degrades to the synthetic finding"() {
        given:
        def check = command('''cat > "$GNOMISH_FINDINGS_FILE" <<'EOF'
{"findings":[{"message":""}]}
EOF
exit 1''')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].message().contains('1')
    }

    def "no findings file content at all falls back to the synthetic finding (regression)"() {
        given:
        def check = command('echo boom-output; exit 1')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        def fail = verdict as Verdict.Fail
        fail.findings().size() == 1
        fail.findings()[0].details().contains('boom-output')
    }

    def "a findings file with content on exit 0 is ignored - verdict stays Pass, and a warning is logged"() {
        given: 'a command that exits 0 but still writes findings-shaped content'
        def check = command('''cat > "$GNOMISH_FINDINGS_FILE" <<'EOF'
{"findings":[{"message":"should be ignored"}]}
EOF
exit 0''')

        when:
        Verdict verdict
        def events = capture(FindingsFileReader) { verdict = runner.run(check, workspace()) }

        then:
        verdict instanceof Verdict.Pass
        events.any { it.formattedMessage.contains('GNOMISH_FINDINGS_FILE has content') }
    }

    def "exit 0 with no findings file content logs no warning"() {
        given: 'a command that exits 0 and writes nothing to the findings file'
        def check = command('exit 0')

        when:
        Verdict verdict
        def events = capture(FindingsFileReader) { verdict = runner.run(check, workspace()) }

        then:
        verdict instanceof Verdict.Pass
        events.every { !it.formattedMessage.contains('GNOMISH_FINDINGS_FILE has content') }
    }

    // FR1, NFR-S3 of add-sandbox-core (task 7.3): the findings path lives in the environment's
    // scratch area, and dispose removes the whole scratch with it after the verdict is classified.
    def "run(...) allocates the findings path in scratch and disposes it after classifying the verdict"() {
        given: 'a command that echoes the findings-file path so the test can inspect it afterward'
        def check = command('echo $GNOMISH_FINDINGS_FILE > "$PWD/.path-marker"; exit 0')

        when:
        runner.run(check, workspace())
        def markerPath = tempDir.resolve('.path-marker')
        def findingsPath = Path.of(Files.readString(markerPath).trim())

        then:
        !Files.exists(findingsPath)
        !Files.exists(findingsPath.parent)
    }

    // FR11, NFR-S1, D11 of add-claim-heartbeat; FR9 of add-sandbox-core: withChildEnv yields a
    // runner whose check process never sees a declared credential name. Observed through the
    // exit-1 synthetic finding's output tail (the runner exposes no env otherwise) and proven
    // against real, always-present base vars (HOME/USER): HOME is in the host base set, so its
    // absence can only come from the credential exclusion.
    def "FR11: withChildEnv excludes the declared credential var from a check's environment"() {
        given: 'an allowlist declaring HOME a credential and a check that reports HOME and USER, then exits 1'
        def credentialAwareRunner = runner.withChildEnv(ChildEnvAllowlist.of([], ['HOME']))
        def check = command('if [ -n "${HOME:-}" ]; then echo HOME=present; else echo HOME=absent; fi; '
                + 'if [ -n "${USER:-}" ]; then echo USER=present; else echo USER=absent; fi; exit 1')

        when:
        def verdict = credentialAwareRunner.run(check, workspace())

        then: 'the credential name is absent, an untouched base name still reaches the check'
        verdict instanceof Verdict.Fail
        def details = (verdict as Verdict.Fail).findings()[0].details()
        details.readLines().contains('HOME=absent')
        details.readLines().contains('USER=present')
    }

    // FR9 of add-sandbox-core: the base set reaches a check with no tracker and no passthrough.
    def "FR11: with no tracker configured a base variable still reaches the check"() {
        given: 'the default runner (empty allowlist) and a check reporting HOME, then exiting 1'
        def check = command('if [ -n "${HOME:-}" ]; then echo HOME=present; else echo HOME=absent; fi; exit 1')

        when:
        def verdict = runner.run(check, workspace())

        then:
        verdict instanceof Verdict.Fail
        (verdict as Verdict.Fail).findings()[0].details().readLines().contains('HOME=present')
    }

    // FR9, M3 of add-sandbox-core (task 7.2): a check's process environment holds ONLY the
    // allowlisted variables — whatever the factory JVM environment holds, nothing outside the host
    // base set plus the factory-set findings variable (plus the shell's own names) leaks in.
    def "M3: no factory environment variable outside the allowlist leaks into a check"() {
        given: 'a check that dumps every environment variable name it sees, then exits 1'
        def check = command('env | cut -d= -f1; exit 1')
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
        def verdict = runner.run(check, workspace())

        then: 'every observed name is allowlisted or shell-internal'
        verdict instanceof Verdict.Fail
        def names = (verdict as Verdict.Fail).findings()[0].details().readLines().findAll { !it.isEmpty() } as Set
        def allowed = (HostTaskExecutionEnvironment.BASE_ENV_NAMES as Set) + ['GNOMISH_FINDINGS_FILE'] + shellSelfSet
        names.every { allowed.contains(it) }
    }
}
