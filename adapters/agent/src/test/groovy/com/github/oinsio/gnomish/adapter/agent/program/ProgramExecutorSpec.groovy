package com.github.oinsio.gnomish.adapter.agent.program

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.executor.RenderedPolicy
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link ProgramExecutor} on the real host path (FR5, FR10, D3, D18 of define-executor-contract): the
 * program is a stand-in preset run as a real process through the launch owner into a host
 * environment — {@code run-as}, whose per-run link names the result section it writes, {@code
 * run-silent}, which writes none, and {@code run-recorded}, which records what it received.
 */
class ProgramExecutorSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    HostTaskExecutionEnvironment environment

    LogCaptureSupport logs

    def setup() {
        environment = new HostTaskExecutionEnvironment(
                workspace, new VirtualClock(), TestChildEnvironment.hostAllowlist(), ProcessLaunch.grouped())
        environment.materialize('gnomish/TASK-7', null)
    }

    def cleanup() {
        logs?.detach()
        environment.dispose()
    }

    private ExecutorResult start(Path program) {
        new ProgramExecutor(ProgramFixtures.declaration([program.toString()]), ProgramFixtures.describe(),
        ['ACME_API_KEY'], environment, ProgramFixtures.equipment(stderrParent))
        .start(ProgramFixtures.request())
    }

    @TempDir
    Path links

    private Path runAs(String section) {
        StandIn.link(Files.createDirectories(links.resolve("run-${UUID.randomUUID()}")).resolve(section), 'run-as')
    }

    // Scenario: executor/result-channel / Stray print does not corrupt the result
    // FR10, D18: a completed run is read from its result file, its usage with cost, wall time and tools
    def "FR10: a completed run is read from the result file, with the usage and cost it reported"() {
        given:
        logs = LogCaptureSupport.attach(ProgramInvocation, Level.DEBUG)

        when:
        def result = start(runAs('completed'))

        then:
        result.status() == Status.COMPLETED
        result.usage().provenance() == UsageProvenance.REPORTED
        result.usage().wallMillis() == 1200
        result.usage().models()['acme-model-1'].input() == 120
        result.usage().models()['acme-model-1'].costUsd() == 0.0031G
        result.usage().tools()*.name() == ['lint']

        and: 'the line it printed is a log line, never the result'
        logs.list.any {
            it.level == Level.DEBUG && it.formattedMessage.contains('a line no reader parses')
        }

        and: 'its stderr directory left with the run'
        Files.list(stderrParent).count() == 0
    }

    // FR7, D18: the executor's own failure classes are read as it wrote them; the class is not the adapter's
    def "FR7: a run reporting failed/#token is read as failed/#token with its reason"() {
        when:
        def result = start(runAs(section))

        then:
        result.status() == Status.FAILED
        result.failureClass() == failureClass
        result.reason().forLog() == reason

        where:
        section | token | failureClass || reason
        'quality' | 'quality' | FailureClass.QUALITY || 'src/Main.java: 2 lint errors remain'
        'limit' | 'limit' | FailureClass.LIMIT || 'turn bound of 20 reached'
    }

    // Scenario: executor/wire-contract / Unknown status fails closed
    // FR2, NFR-R3: an unknown status token reads as failed/infrastructure naming it
    def "FR2: a result whose status is unknown is failed/infrastructure naming the token"() {
        when:
        def result = start(runAs('unknown-status'))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains('done')
    }

    // FR6: a result file that is not a result, or none at all, is the run's failed/infrastructure
    def "FR6: a run whose result is #what is failed/infrastructure saying so, with the exit named when no file exists"() {
        when:
        def result = start(what == 'missing' ? StandIn.executor('run-silent') : runAs(what))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().contains(says)

        where:
        what || says
        'malformed' || 'wrote a malformed result'
        'missing' || 'exited 0 and wrote no result to GNOMISH_RESULT_FILE'
    }

    // Scenario: executor/result-channel / Result path is factory-chosen
    // Scenario: executor/result-channel / The request carries no credential
    // Scenario: executor/wire-contract / Policy is not a vendor tool name
    // FR5, NFR-S1: the request is the child's stdin, the result paths are fresh scratch paths, the key travels in the environment only
    def "FR5, NFR-S1: the child reads the whole request on stdin, writes to fresh scratch paths, and receives the key in its environment only"() {
        given:
        def program = StandIn.recording(links, 'run-recorded')

        when:
        def result = start(program)
        def block = StandIn.log(program).toFile().text

        then:
        result.status() == Status.COMPLETED
        def resultPath = (block =~ /GNOMISH_RESULT_FILE=\[([^\]]+)\]/)[0][1] as String
        def answerPath = (block =~ /GNOMISH_ANSWER_FILE=\[([^\]]+)\]/)[0][1] as String
        resultPath.startsWith(environment.scratchRoot() + '/')
        answerPath.startsWith(environment.scratchRoot() + '/')
        resultPath != answerPath
        block.contains("ACME_API_KEY=[${ProgramFixtures.KEY_VALUE}]")

        and: 'the request it read is the whole request, and holds no credential value'
        def request = new String(environment.readFile(answerPath + '.request', 1 << 20).get(), StandardCharsets.UTF_8)
        request.contains('"role":"transform"')
        request.contains('"instructions":"build it\\n"')
        request.contains('"policy":{"kind":"workspaceWrite"}')
        !request.contains(ProgramFixtures.KEY_VALUE)

        and: 'its policy is the abstract token alone, and no vendor tool identifier appears in it'
        RenderedPolicy.abstractOnly(request)
        RenderedPolicy.vendorWords(request) == []
    }

    // FR5: the stderr tail of a failed run is quoted by its reason, as the agent round keeps it
    def "FR5: a run that fails quotes the end of its stderr, and a successful one leaves it as one DEBUG line"() {
        given:
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        def failed = start(runAs('malformed'))
        def completed = start(runAs('completed'))

        then:
        failed.reason().contains('its stderr ended with: executor: warming up the cache')
        completed.status() == Status.COMPLETED
        logs.list.count {
            it.level == Level.DEBUG && it.formattedMessage.contains('executor: warming up the cache')
        } == 1
    }

    // FR4: describe answers what the preflight obtained, spawning nothing
    def "FR4: describe answers the describe the executor was built with"() {
        given:
        def described = ProgramFixtures.describe()

        expect:
        new ProgramExecutor(ProgramFixtures.declaration(['unused']), described, [], environment,
        ProgramFixtures.equipment(stderrParent)).describe().is(described)
    }
}
