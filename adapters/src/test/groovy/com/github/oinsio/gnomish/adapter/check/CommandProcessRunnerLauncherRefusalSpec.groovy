package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.RefusingLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 as amended 2026-10-17, second, of fix-terminal-receipt-convergence: a command
 * check is a streaming waiter — its stderr is merged into its output, and its verdict is the exit
 * code — so it adds no classifier of the launcher's refusal; the refusal ends the check on the
 * launcher's exit code, and its line is in the output tail the operator reads.
 */
class CommandProcessRunnerLauncherRefusalSpec extends Specification {

    @TempDir
    Path workingCopy

    // Scenario: lifecycle/subprocess-supervision / A launcher's refusal reaches a streaming waiter in its diagnostics
    def "FR21: a check the launcher could not exec ends on the launcher's code, its refusal line in the output tail"() {
        given: 'a host environment whose launcher started and could not exec the shell'
        def environment = new HostTaskExecutionEnvironment(
                workingCopy, new VirtualClock(), TestChildEnvironment.hostAllowlist(), new RefusingLaunch())

        when:
        def outcome = new CommandProcessRunner('sh', new VirtualClock()).run(new VerifyCheck.Command('true'), environment)

        then:
        outcome.exitCode() == 127
        outcome.outputTail() == RefusingLaunch.line('sh').trim()
    }
}
