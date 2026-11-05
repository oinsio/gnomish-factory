package com.github.oinsio.gnomish.adapter.agent.command

import com.github.oinsio.gnomish.adapter.command.CommandRun
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The {@code command} built-in (FR4, FR5, NFR-S1, NG1 of add-command-executor; design D1, D2, D5)
 * over a real host environment whose process is a {@link FakeProcess} handed back by its {@link
 * ProcessLaunch}: the launch records the builder the environment composed and does what the command
 * would before it hands the fake back, already exited, so no feature rests on a thread order.
 */
@Timeout(60)
class CommandStageExecutorSpec extends Specification {

    /** What the factory's own process environment holds: the base, an operator variable, a tracker token, a decision file, noise. */
    static final Map<String, String> FACTORY_ENV = [
        PATH: '/usr/bin', HOME: '/home/gnome', OPERATOR_TOOL_HOME: '/opt/tool', GITHUB_TOKEN: 'ghp_secret',
        GNOMISH_DECISION_FILE: '/tmp/decision.json', UNRELATED_CLOUD_KEY: 'aws-secret']

    @TempDir
    Path workspace

    ProcessBuilder seen

    /** One start of {@code request} whose process exits with {@code code} after {@code effect} ran in the working copy. */
    private ExecutorResult start(int code, Closure effect = {}, ChildEnvAllowlist allowlist = ChildEnvAllowlist.none()) {
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            seen = builder
            effect(builder.directory().toPath())
            def process = FakeProcess.running()
            process.exit(code)
            process
        } as ProcessLaunch
        def environment = new HostTaskExecutionEnvironment(workspace, new VirtualClock(), allowlist, launch)
        environment.materialize('gnomish/TASK-7', null)
        try {
            new CommandExecutorFactory(new VirtualClock()).executor(environment)
                    .start(new CommandExecutorFactory(new VirtualClock()).request(CommandFixtures.round()))
        } finally {
            environment.dispose()
        }
    }

    // Scenario: executor/command-executor / Exit zero completes the round
    def "FR5: a command that exits 0 is completed, with no answer, no reason and a known zero of usage"() {
        when:
        def result = start(0)

        then:
        result.status() == Status.COMPLETED
        result.failureClass() == null
        result.reason() == null
        result.answer() == null
        result.denials().isEmpty()

        and: 'a known zero of model use (D8: REPORTED, never ABSENT), and the wall time is measured'
        result.usage().provenance() == UsageProvenance.REPORTED
        result.usage().models().isEmpty()
        result.usage().tools().isEmpty()
        result.usage().wallMillis() != null
    }

    def "FR4: the declared command runs as sh -c over the working copy, with nothing on stdin"() {
        when:
        start(0)

        then:
        seen.command() == [
            CommandRun.POSIX_SHELL,
            '-c',
            CommandFixtures.COMMAND
        ]
        seen.directory().toPath() == workspace
        seen.redirectErrorStream()
    }

    // Scenario: executor/command-executor / Tracker token is absent from the command
    def "NFR-S1: the command's environment is the layered allowlist alone — no tracker token, nothing unlisted"() {
        given: 'the factory holds every variable; the operator passes one through; the tracker declares its token'
        def allowlist = ChildEnvAllowlist.of(['OPERATOR_TOOL_HOME'], ['GITHUB_TOKEN'], { String name ->
            Optional.ofNullable(FACTORY_ENV[name])
        } as ChildEnvValues)

        when:
        start(0, {}, allowlist)

        then: 'the host base and the operator passthrough, and no factory-set variable at all'
        seen.environment() == [PATH: '/usr/bin', HOME: '/home/gnome', OPERATOR_TOOL_HOME: '/opt/tool']
    }

    def "NG1: no decision file variable reaches the command, though the factory's environment holds one"() {
        given:
        def allowlist = ChildEnvAllowlist.of([], [], { String name ->
            Optional.ofNullable(FACTORY_ENV[name])
        } as ChildEnvValues)

        when:
        start(0, {}, allowlist)

        then:
        !seen.environment().containsKey('GNOMISH_DECISION_FILE')
    }

    def "NG1: a decision-request file the command writes into the working copy is no answer: the round completes"() {
        when:
        def result = start(0, { Path dir ->
            Files.writeString(dir.resolve('decision-request.json'), '{"question":"ship it?","options":["yes","no"]}')
        })

        then: 'the file is there, and classification followed the exit code only'
        Files.exists(workspace.resolve('decision-request.json'))
        result.status() == Status.COMPLETED
        result.answer() == null
    }

    def "FR8: describe lists transform alone, no poll, no credentials and no usage report"() {
        when:
        Describe describe = new CommandStageExecutor(Stub(TaskExecutionEnvironment), new CommandRun(CommandRun.POSIX_SHELL, new VirtualClock())).describe()

        then:
        describe.protocolVersion() == Describe.SPOKEN_PROTOCOL_VERSION
        describe.roles() == [Role.TRANSFORM]
        !describe.capabilities().contains(Capability.POLL)
        describe.credentials().isEmpty()
        describe.egress().isEmpty()
        describe.reportsUsage()
        describe.settingsSchema().get('type').asText() == 'object'
    }

    // Scenario: executor/command-executor / Poll is never pending
    def "FR8: a poll of the command built-in is failed/infrastructure, never pending, and runs nothing"() {
        given:
        def request = new CommandExecutorFactory(new VirtualClock()).request(CommandFixtures.round())

        when:
        def result = new CommandStageExecutor(Stub(TaskExecutionEnvironment), new CommandRun(CommandRun.POSIX_SHELL, new VirtualClock()))
                .poll(request, new PendingKey('k-1'))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.pendingKey() == null
        result.reason().raw().contains('never pending')
        result.usage().provenance() == UsageProvenance.REPORTED
        result.usage().models().isEmpty()
    }
}
