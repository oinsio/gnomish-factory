package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.adapter.agent.command.CommandStageExecutor
import com.github.oinsio.gnomish.adapter.command.CommandRun
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR8 of add-command-executor (design D2): the {@code command} built-in through the conformance kit of
 * define-executor-contract, driven in process by {@link BuiltInCandidate} — the mirror interface alone,
 * no document on a wire. The built-in runs the kit's {@code transform} instructions as its command, in
 * a real host environment whose launch hands back a {@link FakeProcess} that exits 0, so the case
 * judges what the built-in reports rather than what a shell makes of the kit's English sentence; the
 * exit-code contract itself is {@code CommandExitCodeSpec}'s.
 */
@Timeout(60)
class CommandBuiltInConformanceSpec extends Specification {

    @TempDir
    Path scratch

    @TempDir
    Path workingCopy

    List<List<String>> launched = []
    List<Duration> waits = []

    // Scenario: executor/command-executor / Built-in passes the conformance kit
    def "FR8: the command built-in passes every applicable case, the wire-only cases and the roles it does not take are not applicable"() {
        given:
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            launched << builder.command()
            def process = FakeProcess.running()
            process.exit(0)
            process
        } as ProcessLaunch
        def clock = new VirtualClock()
        def environment = new HostTaskExecutionEnvironment(workingCopy, clock, ChildEnvAllowlist.none(), launch)
        environment.materialize('gnomish/TASK-1', null)
        def builtIn = new CommandStageExecutor(environment, new CommandRun(CommandRun.POSIX_SHELL, clock))

        when:
        def report
        try {
            report = new ConformanceKit([:], { Duration d ->
                waits << d
            } as Sleeper).run(new BuiltInCandidate(builtIn, scratch))
        } finally {
            environment.dispose()
        }
        def verdicts = report.verdicts()

        then: 'no case failed; the four the built-in can be judged on passed'
        report.failures() == 0
        verdicts.findAll {
            it.value instanceof CaseVerdict.Passed
        }.keySet().toList() == [
            KitCase.DESCRIBE,
            KitCase.SETTINGS_SCHEMA,
            KitCase.TRANSFORM,
            KitCase.UNKNOWN_ROLE
        ]

        and: 'the wire-only cases do not apply in process'
        KitCase.values().findAll { it.wireOnly() }.every {
            (verdicts[it] as CaseVerdict.NotApplicable).reason().contains('only on the wire')
        }

        and: 'nor do the roles it does not describe, nor the poll it does not declare'
        (verdicts[KitCase.VERDICT] as CaseVerdict.NotApplicable).reason().contains('verdict')
        (verdicts[KitCase.DECISION] as CaseVerdict.NotApplicable).reason().contains('decision')
        (verdicts[KitCase.MALFORMED_ANSWER] as CaseVerdict.NotApplicable).reason().contains('decision')
        (verdicts[KitCase.PENDING_POLL] as CaseVerdict.NotApplicable).reason().contains('poll')
        waits == []

        and: 'the transform case ran the instructions once as sh -c; the unknown-role start ran nothing'
        launched == [
            [
                CommandRun.POSIX_SHELL,
                '-c',
                'Make the change the conformance case asks for.'
            ]
        ]
        Files.isDirectory(scratch.resolve('transform'))
    }
}
