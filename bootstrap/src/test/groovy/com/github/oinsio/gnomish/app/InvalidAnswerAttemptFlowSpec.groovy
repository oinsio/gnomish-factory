package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path

/**
 * An unparseable answer file burns one attempt, and the next attempt is given its finding (FR20 of
 * extract-agent-round-runner; design D15, part 3), on the real flow of both branch modes: the engine
 * over the round adapters a run wires — in a box, the {@code claude} entry's stage half over the
 * sandboxed round source and its persistence; on the host, the stage chain a git-mode {@code run}
 * assembles through its production owners ({@link RoundFlowSpecBase#runHostAssembled}). The gnome is
 * the fake agent's {@code garbage-then-plain}: its first round leaves text that is not JSON at the
 * round's answer path, every later round in the same working copy is {@code plain-round}. The prompts
 * are the fake agent's stdin, captured; the second prompt's finding quotes what the first round wrote.
 */
class InvalidAnswerAttemptFlowSpec extends RoundFlowSpecBase {

    static final String SCENARIO = 'garbage-then-plain'

    /** One stage, {@code build}, with no check, so the second round's completion ends the run. */
    static PipelineDefinition unchecked() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1.0.0', new AutonomyLimits(3), [stage])
    }

    // Scenario: executor/agent-round-runner / An unparseable answer file burns one attempt
    def "FR20: in #mode mode, an unparseable answer file burns one attempt and the next attempt's prompt carries its finding"() {
        given: 'the fake agent capturing each round\'s prompt'
        def capture = Files.createDirectories(tempDir.resolve('capture')).resolve(SCENARIO + '.log')
        def agent = new FactoryProperties('factory-01', FakeAgentSupport.binaryCapturingStdin(SCENARIO, capture), null, null)

        when:
        def outcome = mode == 'container'
                ? run(unchecked(), localBox(), agent, new ScriptedCheckRounds())
                : runHostAssembled(unchecked(), worktree(), agent)

        then: 'the run completes, after two rounds'
        outcome instanceof TaskOutcome.Completed
        outcome.finalState().totals().rounds() == 2

        and: 'two rounds ran, one per attempt: no second request for the first'
        def prompts = capture.toFile().text.split('(?m)^---$')*.trim().findAll {
            !it.isEmpty()
        }
        prompts.size() == 2

        and: 'the first round had no feedback; the second is given the finding naming the parse error'
        !prompts[0].contains('wrote a malformed answer file')
        !prompts[0].contains('=== Rework, not restart ===')
        def feedback = prompts[1].substring(prompts[1].indexOf('=== Prior-attempt feedback ==='))
        feedback.readLines()[1].endsWith(': failed')
        feedback.readLines()[2].startsWith("    * agent executor 'claude': wrote a malformed answer file: ")

        and: 'the finding quotes what the fake agent wrote, so the next attempt sees its own answer'
        def written = getClass().getResource("/fake-agent/scenarios/${SCENARIO}/decision.json").text.trim()
        feedback.readLines()[2].contains('; it wrote: ' + written)

        and: 'the second round is the stage\'s next attempt, a rework: the first attempt was burned'
        prompts[1].contains('=== Rework, not restart ===')

        where:
        mode << ['container', 'host']
    }
}
