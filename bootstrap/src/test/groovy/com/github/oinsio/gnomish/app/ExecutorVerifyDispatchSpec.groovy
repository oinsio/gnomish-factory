package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12, UX2 of define-executor-contract: {@code ManualRunRunner} routes {@code gnomish executor
 * verify} to the conformance kit's command after the git floor — the kit initialises git working
 * copies — and never into the run flow; the configuration loader resolves no project for it.
 */
class ExecutorVerifyDispatchSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path clonePath

    @TempDir
    Path homeDir

    private PrintStream originalOut
    private ByteArrayOutputStream out

    def setup() {
        originalOut = System.out
        out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
    }

    def cleanup() {
        System.out = originalOut
    }

    // FR12: the runner hands the command line to the kit, which prints one line per case
    def "FR12: 'gnomish executor verify' reaches the conformance kit, not the run flow"() {
        given:
        def runner = newManualRunRunner(clonePath, homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]), TaskGitFixture.real())

        when:
        runner.run(new DefaultApplicationArguments('executor', 'verify', StandIn.executor('break-verdict').toString()))

        then:
        thrown(ExecutorVerifyFailedException)
        def lines = out.toString('UTF-8').readLines()
        lines.size() == 12
        lines.contains('FAIL verdict: completed with no answer, although role verdict needs one')
    }

    // FR12: the kit works in no project, so the configuration target names none and refuses nothing
    def "FR12: the configuration target of an executor command line is no project"() {
        given:
        List<String> refusals = []

        when:
        def target = ConfigurationTarget.of(new DefaultApplicationArguments('executor', 'verify', 'acme'), refusals)

        then:
        target.name() == null
        target.resolve(null, refusals) == null
        refusals == []
    }
}
