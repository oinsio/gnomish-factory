package com.github.oinsio.gnomish.app

import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.GATE
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.gate
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.script
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.tlsGate
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.EgressProperties
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.LoopbackTlsFixture
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.github.tomakehurst.wiremock.WireMockServer
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLContext
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2 of migrate-verify-to-executor-contract (design D21): the in-place run has no task branch and
 * no resume, so a check that answers {@code pending} keeps its key in the run's memory. The run is
 * assembled through the production assembly exactly as the in-place drive assembles it, over a
 * workspace that is not a git repository and the drive's in-memory persistence; its one check is
 * the {@code http} built-in judging a gate on loopback TLS that answers {@code RUNNING} on the start
 * and {@code OK} on the poll.
 */
class InPlacePendingKeySpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path tempDir

    WireMockServer server

    SSLContext previous = SSLContext.getDefault()

    def setup() {
        server = tlsGate(tempDir.resolve('tls'))
    }

    def cleanup() {
        server.stop()
        LoopbackTlsFixture.restore(previous)
    }

    // Scenario: verification/pending-poll / An in-place run keeps the key in memory
    def "FR2: a pending check of a git-less in-place run keeps its key in memory, writes no commit, and is polled to its verdict"() {
        given: 'a workspace with the law file and no repository, and a stage verified by the gate'
        def workspace = Files.createDirectories(tempDir.resolve('workspace'))
        Files.createDirectories(workspace.resolve('.gnomish'))
        Files.writeString(workspace.resolve('.gnomish/instructions.md'), 'build it\n')
        def check = gate(server)
        script(server, ['RUNNING', 'OK'])
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [check], new AutonomyLimits(3), AdvancementMode.AUTO)
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage])
        def context = new TaskContext('inplace-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), List.<Decision> of())
        def persistence = new InMemoryAttemptPersistence()
        def properties = new FactoryProperties('test-instance', FakeAgentSupport.wrapperFor('plain-round'), null, null,
                null, null, null, null, new EgressProperties([], ['loopback']))
        def assembly = newAssembly(null, new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'), properties,
                VirtualTimeEquipment.on(new VirtualClock()))

        when: 'the in-place drive\'s assembly runs the stage'
        def run = assembly.assemble(new RunOrder(workspace, null, definition, false), context,
                TaskState.atStageStart('build'), persistence, [], new DescribedLaw(LawBinding.workingTree(workspace), [:]))
        def outcome = new Engine().run(definition, context, TaskState.atStageStart('build'),
                new DirectoryWorkspace(workspace), run.ports())

        then: 'the key was held in the run\'s memory: one pending write naming the check, with no round token'
        persistence.pendingWrites().size() == 1
        def entries = persistence.pendingWrites()[0].state().pendingPolls()
        entries*.subject() == [
            new PendingSubject.Check(CheckRef.of(0, check))
        ]
        entries[0].token() == null

        and: 'the poll proceeded: the gate was asked on the start and once more, and the check passed'
        server.countRequestsMatching(getRequestedFor(urlEqualTo(GATE)).build()).count == 2
        outcome instanceof TaskOutcome.Completed
        persistence.entries().last().state().attempts().last().checkResults()*.verdict().every {
            it instanceof Verdict.Pass
        }

        and: 'no commit was written: the workspace never became a repository'
        !Files.exists(workspace.resolve('.git'))
    }
}
