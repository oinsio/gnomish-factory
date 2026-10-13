package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR2 of add-sandbox-core (design D20): a round in host mode, through the host adapter the
 * executor builds per round — the engine, the fake agent as a real local process, a git worktree
 * of the factory clone as the working copy, the host attempt persistence. Isolation mechanics stay
 * host-shaped, and so is the decision file's medium (a per-round temp file); the rest of the process
 * discipline is the sandboxed modes' own.
 */
class HostModeRoundSpec extends RoundFlowSpecBase {

    private List<String> branchCommits() {
        commitsIn(clone, 'HEAD..' + BRANCH)
    }

    // Scenario: sandbox/execution-environment / Host adapter keeps worktree mechanics
    def "FR2: the host round runs a local process in a worktree of the factory clone and closes as one commit, fetching nothing"() {
        given: 'the working copy is a worktree of the factory clone, on the task branch'
        def worktree = worktree()
        def before = branchCommits()

        when:
        def outcome = run(worktree, FakeAgentSupport.propertiesFor('plain-round'))

        then: 'the working copy shares the factory clone\'s repository'
        outcome instanceof TaskOutcome.Completed
        Path.of(gitOutput(worktree, 'rev-parse', '--path-format=absolute', '--git-common-dir')).toRealPath() ==
                clone.resolve('.git').toRealPath()

        and: 'the agent ran as a local process with the worktree as its directory: its file is there'
        Files.exists(worktree.resolve('output.txt'))

        and: 'the round closed as one commit carrying both the gnome\'s work and the factory\'s state, with no snapshot'
        def added = branchCommits() - before
        added.size() == 1
        def changed = gitOutput(clone, 'show', '--name-only', '--format=', added[0]).readLines()
        changed.containsAll([
            'output.txt',
            '.gnomish-task/state.json'
        ])
        !subjectOf(clone, added[0]).startsWith('gnomish: snapshot')

        and: 'nothing was fetched into the factory clone: the branch moved by commits only'
        !gitOutput(clone, 'reflog', 'show', '--format=%gs', BRANCH).contains('fetch')
    }

    // F83: scenario text aligned to what this test asserts
    // Scenario: sandbox/execution-environment / Mode-independent discipline applies in host mode
    def "FR2: in host mode the prompt arrives on stdin from the bound law, and the gnome's question travels outside the branch"() {
        given: 'a worktree whose own copy of the instructions differs from the law the run is bound to'
        def worktree = worktree()
        Files.writeString(worktree.resolve('instructions.md'), 'WORKTREE COPY')
        def capture = Files.createDirectories(tempDir.resolve('capture')).resolve('decision-needed.log')
        def agent = new FactoryProperties('factory-01', FakeAgentSupport.binaryCapturingStdin('decision-needed', capture), null, null)

        when: 'the gnome asks a question'
        def outcome = run(worktree, agent)

        then: 'the prompt reached the agent on stdin, carrying the bound law and not the worktree copy'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.DecisionNeeded
        def prompt = Files.readString(capture)
        prompt.contains('Do the thing.')
        !prompt.contains('WORKTREE COPY')

        and: 'the question reached the factory through the per-round temp file: none is in the branch'
        gitOutput(clone, 'ls-tree', '-r', '--name-only', BRANCH, '--', '.gnomish-task/decisions/').readLines().isEmpty()
    }

    private TaskOutcome run(Path worktree, FactoryProperties agent) {
        runHost(pipeline(3), worktree, agent, ScriptedCommandCheckRunner.fixed(new Verdict.Pass(null)))
    }
}
