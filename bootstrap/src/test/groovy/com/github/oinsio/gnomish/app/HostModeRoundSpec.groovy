package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
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
    def "FR2, FR19: the host round runs a local process in a worktree of the factory clone and closes a completed round as its snapshot plus one round commit, fetching nothing"() {
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

        and: 'the completed round closed as its snapshot, carrying the gnome\'s work, and exactly one round commit above it'
        def added = branchCommits() - before
        added.size() == 2
        subjectOf(clone, added[0]).startsWith('gnomish: snapshot build#0')
        gitOutput(clone, 'show', '--name-only', '--format=', added[0]).readLines().contains('output.txt')
        gitOutput(clone, 'rev-parse', added[1] + '^') == added[0]
        !subjectOf(clone, added[1]).startsWith('gnomish: snapshot')
        gitOutput(clone, 'show', '--name-only', '--format=', added[1]).readLines().contains('.gnomish-task/state.json')

        and: 'nothing was fetched into the factory clone: the branch moved by commits only'
        !gitOutput(clone, 'reflog', 'show', '--format=%gs', BRANCH).contains('fetch')
    }

    // Scenario: sandbox/execution-environment / Host adapter keeps worktree mechanics
    def "FR2, FR19: a host round with no snapshot, one that asked a question, closes with the single round commit"() {
        given:
        def worktree = worktree()
        def before = branchCommits()

        when: 'the gnome asks a question: the round completed, but the engine does not verify it'
        def outcome = run(worktree, FakeAgentSupport.propertiesFor('decision-needed'))

        then: 'one commit carries the round\'s state, and none is a snapshot'
        outcome instanceof TaskOutcome.Escalated
        def added = branchCommits() - before
        added.size() == 1
        !subjectOf(clone, added[0]).startsWith('gnomish: snapshot')
        gitOutput(clone, 'show', '--name-only', '--format=', added[0]).readLines().contains('.gnomish-task/state.json')
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
        runHost(pipeline(3), worktree, agent, ScriptedCheckRounds.fixed(new Verdict.Pass(null, [])))
    }
}
