package com.github.oinsio.gnomish.adapter.console

import com.github.oinsio.gnomish.adapter.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * FR4: the interactive {@link InteractiveExternalCheckClient} prompts once per
 * poll — {@code pass} / {@code fail} / {@code running} — collecting findings on
 * {@code fail}; unrecognized input re-prompts (UX1).
 */
class InteractiveExternalCheckClientSpec extends Specification {

    private static VerifyCheck.External sampleCheck() {
        new VerifyCheck.External('ci-build', Duration.ofSeconds(30), Duration.ofMinutes(5))
    }

    private static Workspace sampleWorkspace() {
        new Workspace() {}
    }

    def "pass answer yields PollStatus.Pass"() {
        given:
        def io = new ScriptedConsoleIO(['pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def client = new InteractiveExternalCheckClient(console)

        when:
        def status = client.poll(sampleCheck(), sampleWorkspace())

        then:
        status instanceof PollStatus.Pass

        and: 'the check identity was printed for operator clarity'
        io.printed.any { it.contains('ci-build') }
    }

    def "running answer yields PollStatus.Running"() {
        given:
        def io = new ScriptedConsoleIO(['running'])
        def console = new DialogConsole(io, { json -> 'status' })
        def client = new InteractiveExternalCheckClient(console)

        when:
        def status = client.poll(sampleCheck(), sampleWorkspace())

        then:
        status instanceof PollStatus.Running
    }

    def "fail answer collects findings until an empty line"() {
        given:
        def io = new ScriptedConsoleIO([
            'fail',
            'build broke',
            'flaky test',
            ''
        ])
        def console = new DialogConsole(io, { json -> 'status' })
        def client = new InteractiveExternalCheckClient(console)

        when:
        def status = client.poll(sampleCheck(), sampleWorkspace())

        then:
        status instanceof PollStatus.Fail
        status.findings().size() == 2
        status.findings()*.message() == ['build broke', 'flaky test']
    }

    def "unrecognized input re-prompts naming the accepted answers before an eventual valid answer"() {
        given:
        def io = new ScriptedConsoleIO(['bogus', 'pass'])
        def console = new DialogConsole(io, { json -> 'status' })
        def client = new InteractiveExternalCheckClient(console)

        when:
        def status = client.poll(sampleCheck(), sampleWorkspace())

        then:
        status instanceof PollStatus.Pass

        and: 'the re-prompt named the accepted answers'
        io.printed.any { it.contains('pass') && it.contains('fail') && it.contains('running') }
    }
}
