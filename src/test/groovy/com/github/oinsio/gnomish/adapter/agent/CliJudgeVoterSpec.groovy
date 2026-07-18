package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, FR9, FR12, FR13, FR15, M2 of add-agent-executor: {@link CliJudgeVoter}'s
 * {@code vote()} assembly against the fake agent binary — clean/fenced/garbage
 * verdict, unreadable criteria file (no process spawned), and a killed process
 * on {@code roundTimeout} expiry (always a normal {@code Vote}, never thrown).
 */
class CliJudgeVoterSpec extends Specification {

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    def setup() {
        Files.writeString(workspaceDir.resolve('criteria.md'), 'The output must be correct.')
    }

    private JudgeVoter voterFor(String scenario) {
        def properties = FakeAgentSupport.propertiesFor(scenario)
        new CliJudgeVoter(properties, clock)
    }

    private VerifyCheck.Judge checkFor(Map<String, Object> settings = [:], String criteriaFile = 'criteria.md') {
        new VerifyCheck.Judge(criteriaFile, 'claude-fake-judge-1', settings, 1)
    }

    private TaskContext context() {
        new TaskContext('TASK-1', 'title', 'body', [])
    }

    // FR8: a clean {"passed": true} final message yields Pass.
    def "clean pass verdict yields Vote(Pass)"() {
        given:
        def voter = voterFor('judge-verdict-pass')

        when:
        def vote = voter.vote(checkFor(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.Pass
    }

    // FR8, FR9: a fenced {"passed": false, ...} final message yields Fail with findings
    // and reports per-model tokens.
    def "fenced fail verdict yields Vote(Fail) with findings and tokens"() {
        given:
        def voter = voterFor('judge-verdict-fail-fenced')

        when:
        def vote = voter.vote(checkFor(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.Fail
        !(vote.verdict() as Verdict.Fail).findings().isEmpty()
        !vote.tokensByModel().isEmpty()
    }

    // FR8, NFR-R1: an unparseable final message yields CannotVerify, not a thrown exception.
    def "garbage verdict yields Vote(CannotVerify)"() {
        given:
        def voter = voterFor('judge-verdict-garbage')

        when:
        def vote = voter.vote(checkFor(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.CannotVerify
    }

    // FR13: an unreadable criteria file yields CannotVerify with no process spawned —
    // the scenario is deliberately invalid so any process launch would fail loudly.
    def "unreadable criteria file yields Vote(CannotVerify) with no process spawned"() {
        given:
        def voter = voterFor('this-scenario-does-not-exist-and-would-fail-if-launched')
        def check = checkFor([:], 'does-not-exist.md')

        when:
        def vote = voter.vote(check, context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.CannotVerify
        vote.tokensByModel().isEmpty()
    }

    // FR13, NFR-R1: roundTimeout expiry kills the process and returns CannotVerify,
    // never a thrown RoundTimeoutException — the judge port never throws.
    def "hung process past roundTimeout yields Vote(CannotVerify), not a thrown exception"() {
        given:
        def voter = voterFor('hangs-forever')

        when:
        def vote = voter.vote(checkFor([roundTimeout: 1]), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.CannotVerify
        vote.tokensByModel().isEmpty()
    }

    // FR4, NFR-R1: a stream with no result event yields CannotVerify, never a thrown exception.
    def "missing result event yields Vote(CannotVerify), not a thrown exception"() {
        given:
        def voter = voterFor('missing-result-event')

        when:
        def vote = voter.vote(checkFor(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.CannotVerify
        vote.tokensByModel().isEmpty()

        and: 'the CannotVerify details carry the exception\'s own message (session id), not its class name'
        def cannotVerify = vote.verdict() as Verdict.CannotVerify
        cannotVerify.details().contains('stream-json carried no result event for round')
        !cannotVerify.details().contains('MissingResultEventException')
    }

    // FR13, NFR-R1: the agent CLI binary failing to start (not merely hanging or
    // producing a bad verdict) yields CannotVerify with no tokens, never a thrown exception.
    def "agent process failing to start yields Vote(CannotVerify)"() {
        given: 'a FactoryProperties pointing at a binary path that cannot be executed'
        def properties = new com.github.oinsio.gnomish.FactoryProperties(
                'factory-01', workspaceDir.resolve('no-such-binary-here').toString(), [])
        def voter = new CliJudgeVoter(properties, clock)

        when:
        def vote = voter.vote(checkFor(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        vote.verdict() instanceof Verdict.CannotVerify
        vote.tokensByModel().isEmpty()

        and: 'the failure reason and details describe the process failing to start, not a round outcome'
        def cannotVerify = vote.verdict() as Verdict.CannotVerify
        cannotVerify.reason() == 'agent CLI process failed to start'
        cannotVerify.details() == workspaceDir.resolve('no-such-binary-here').toString()
    }

    // FR7, D10, task 9.4: a supplied AgentProgressListener receives the round's live progress —
    // judge rounds feed the same listener slot as executor rounds.
    def "supplied progress listener receives round-started and round-finished events"() {
        given:
        def properties = FakeAgentSupport.propertiesFor('judge-verdict-pass')
        def events = []
        AgentProgressListener listener = { AgentProgressEvent event -> events << event }
        def voter = new CliJudgeVoter(properties, clock, listener)

        when:
        voter.vote(checkFor(), context(), new DirectoryWorkspace(workspaceDir))

        then:
        events.any { it instanceof AgentProgressEvent.RoundStarted }
        events.any { it instanceof AgentProgressEvent.RoundFinished }
    }
}
