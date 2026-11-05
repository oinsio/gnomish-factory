package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR9, NFR-R1, M1, M2, G2 of add-command-executor (task 7.1), host mode: the fresh git-mode {@code run}
 * path, assembled through the composition root's own wiring, runs {@link CommandStagePipeline}'s
 * mixed pipeline — a {@code command} stage whose declared shell command fails on its first round and
 * passes on its second, then an {@code agent-cli} stage — against a real task worktree. The command
 * reaches the worktree through the dispatch's {@code COMMAND} arm, the registry's built-in and the
 * round's host environment; nothing in this spec stands between them.
 *
 * <p>Read from the effects only the real flow produces: each round's own commit on the task branch
 * (its tree and its state file), and the fake agent's argv capture, which records every model
 * invocation the run made.
 */
@Timeout(120)
class CommandStageHostFlowSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    RegisteredClone registeredClone
    Path agentInvocations

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'command-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        agentInvocations = Files.createDirectories(tempDir.resolve('agent')).resolve('plain-round.log')
    }

    // Scenario: executor/command-executor / Flaky command retries with its finding in feedback
    // Scenario: executor/command-executor / Command effects are the round product
    // FR9, NFR-R1, G3, M1, M2: a flaky command retries in the same working copy with its finding as
    // feedback, both rounds' effects committed, and no model invoked for the command stage
    def "FR9, NFR-R1: in host mode a flaky command stage retries with its finding, each round's effects committed and no model invoked"() {
        given: 'a git-mode runner whose fake agent records every invocation it receives'
        def runner = new GitModeRunner(
                newAssembly(new ByteArrayInputStream(new byte[0]), System.out,
                FakeAgentSupport.propertiesCapturingArgv('plain-round', agentInvocations)),
                TaskGitFixture.real(), registeredClone, LiveConsoleIO.onStdout())
        def records = new CommandStageRecords(this, cloneDir, 'gnomish/CMD-1')

        when:
        runner.run(new RunOrder(cloneDir, null, CommandStagePipeline.law(), false),
                context('CMD-1'), TaskState.atStageStart('build'))

        then: "the first round's commit carries the marker the failing command wrote, and no build result"
        records.treeOf('build', 0).contains(CommandStagePipeline.MARKER)
        !records.treeOf('build', 0).contains(CommandStagePipeline.RESULT)

        and: "the second round ran in the same working copy: it saw the marker, passed, and its commit carries the result"
        records.treeOf('build', 1).containsAll([
            CommandStagePipeline.MARKER,
            CommandStagePipeline.RESULT
        ])

        and: "the first round is recorded as a quality failure whose one finding names the command, its exit code and its output tail"
        records.attempt('build', 0).get('result').asText() == 'qualityFailure'
        records.failedFindings('build', 0) == [CommandStagePipeline.FINDING]
        records.checkRefs('build', 0) == CommandStagePipeline.EXECUTOR_CHECK

        and: "the second round opened on that record, the prior feedback its request is built from; only it ran the verify chain"
        records.failedFindings('build', 0, 1) == [CommandStagePipeline.FINDING]
        records.checkRefs('build', 1) == CommandStagePipeline.VERIFY_CHECK
        records.attempt('build', 1).get('result').asText() == 'passed'

        and: "both command rounds are the built-in's: no model used, wall time recorded"
        records.isCommandRound('build', 0)
        records.isCommandRound('build', 1)

        and: 'the run made exactly one model invocation, the agent stage'
        FakeAgentSupport.capturedInvocations(agentInvocations).size() == 1
        records.participant('review', 0).get('executor').asText() == 'agent-cli'
    }
}
