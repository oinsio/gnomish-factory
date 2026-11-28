package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.briefing.BriefingSections
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Identity spec of design D4 as amended 2026-11-21 and the D8 row "The question a decision answers"
 * of extract-agent-round-runner ({@code testing.md}, "Invariant specs across a flow"): the question
 * and options of the park commit's report, those of the decision in the decision commit, and those
 * in the next round's prompt are one value — on the real medium, a bare origin, in both execution
 * modes.
 *
 * <p>A gnome that asks (the fake agent's {@code decision-needed}) parks a task; a second instance
 * then starts the round after the answer from the record alone — on the host a fresh clone of
 * origin registered in its own home, which lands the decision commit and runs the next round
 * through {@code run --resume --decision}; in container mode the decision commit lands through the
 * real {@code run --resume} arm and the next round starts in a fresh box from the task the decision
 * commit records. Every round's prompt and argv are captured by the agent stand-in.
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class DecisionQuestionIdentitySpec extends Specification implements AppAssemblyFixture {

    private static final String TASK = 'ASK-1'
    private static final String SCENARIO = 'decision-needed'
    private static final String REPLY = 'refactor, keep the public API'
    private static final List<String> SESSION_FLAGS = [
        '--resume',
        '--continue',
        '--session-id'
    ]

    @TempDir
    Path tempDir

    // FR9, NFR-R2: park report == decision commit's decision == next round's prompt, and no session flag
    // Scenario: git/git-task-persistence / The reply and its question land in one commit
    // Scenario: executor/agent-executor / Question, options and reply reach the next round
    // Scenario: executor/agent-executor / Any instance starts the round after an answer alike
    def "FR9, NFR-R2: on #medium the question the gnome asked is the question the decision records and the next round is told"() {
        given: 'a task parked on a decision request, on origin'
        def definition = ParkPipelines.asking()
        def parking = medium == 'host'
                ? new HostContinuationMedium(tempDir.resolve('host'))
                : new ContainerContinuationMedium(tempDir.resolve('container'))
        parking.park(TASK, definition, SCENARIO)
        def history = new BranchHistory(parking.origin(), TASK)
        String park = history.tip()
        def asked = (history.record(park).outcome() as RecordedOutcome.Escalated).report() as EscalationReport.DecisionNeeded

        and: 'an agent that records every round it is started for'
        Path standIn = Files.createDirectories(tempDir.resolve('stand-in'))
        Path prompts = standIn.resolve("${SCENARIO}.log")
        def agent = testProperties(agentCliBinary: StandIn.link(standIn.resolve(SCENARIO), 'agent-capturing-round').toString())

        when: 'a second instance answers and starts the next round'
        String decided
        if (medium == 'host') {
            new HostContinuationMedium(tempDir.resolve('second'), parking.origin())
                    .resume(TASK, definition, agent, REPLY, TaskGitFixture.real())
            decided = history.after(park).first()
        } else {
            parking.resume(TASK, definition, SCENARIO, REPLY)
            decided = history.after(park).first()
            def record = history.record(decided)
            (parking as ContainerContinuationMedium).drive(TASK, definition, agent, record.context(), history.state(decided))
        }
        def decisions = history.record(decided).context().decisions()
        Decision decision = decisions.last()
        String nextPrompt = Files.readString(prompts).split('\n---\n').first()

        then: 'the gnome asked what the fake agent scripts'
        asked.question() == UntrustedText.agent('Refactor or patch?')
        asked.options() == [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ]

        and: 'the reply and the question it answers land in the one commit after the park'
        history.parent(decided) == park
        decision.body() == REPLY
        decision.answers() == asked
        decision.answers().question() == asked.question()
        decision.answers().options() == asked.options()

        and: "the next round's prompt renders the decision the record holds, question, options and reply"
        nextPrompt.contains(rendered(decisions))
        nextPrompt.contains("- question: ${asked.question().forConsole()}\n")
        asked.options().every {
            nextPrompt.contains("    * ${it.forConsole()}\n")
        }
        nextPrompt.contains("reply (operator): ${REPLY}\n")

        and: 'no round was launched with a session flag'
        def argvs = FakeAgentSupport.capturedInvocations(standIn.resolve("${SCENARIO}.argv"))
        !argvs.isEmpty()
        argvs.every { argv -> argv.every { !(it in SESSION_FLAGS) } }

        where:
        medium << ['host', 'container']
    }

    // FR9: a reply to an escalation that asked nothing records no question
    // Scenario: git/git-task-persistence / A reply to another escalation records no question
    def "FR9: on #medium a reply to an exhausted stage records the reply and no question"() {
        given: 'a task parked as escalated(AttemptsExhausted), on origin'
        ContinuationMedium m = medium == 'host'
                ? new HostContinuationMedium(tempDir.resolve('host'))
                : new ContainerContinuationMedium(tempDir.resolve('container'))
        def definition = ParkPipelines.escalating()
        m.park(TASK, definition, 'plain-round')
        def history = new BranchHistory(m.origin(), TASK)
        String park = history.tip()

        expect:
        (history.record(park).outcome() as RecordedOutcome.Escalated).report() instanceof EscalationReport.AttemptsExhausted

        when: 'the task is returned with a reply'
        m.resume(TASK, definition, 'plain-round', 'retry after the fix')
        String decided = history.after(park).first()
        Decision decision = history.record(decided).context().decisions().last()

        then:
        history.parent(decided) == park
        decision.body() == 'retry after the fix'
        decision.answers() == null

        where:
        medium << ['host', 'container']
    }

    private static String rendered(List<Decision> decisions) {
        def out = new StringBuilder()
        BriefingSections.renderDecisions(out, decisions)
        out.toString()
    }
}
