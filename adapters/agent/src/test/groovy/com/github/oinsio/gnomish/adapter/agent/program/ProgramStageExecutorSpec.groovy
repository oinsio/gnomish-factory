package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.DenialRead
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link ProgramStageExecutor} (FR10, FR14, FR21 of define-executor-contract): a round is opened
 * through the mode's round source, the guard is probed around the run, the round is closed through
 * the source and its denials read at the close; the request carries the merged settings, the
 * model and the prior findings; a failure of the round's own mechanics discards it and is that
 * round's {@code failed/infrastructure} with the drained denials. The program is a stand-in preset
 * run as a real process in a host environment; the round source is the spec's.
 */
class ProgramStageExecutorSpec extends Specification {

    static final Denial EGRESS = Denial.unidentified(new Finding('egress denied: evil.example', null, null))

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    @TempDir
    Path links

    HostTaskExecutionEnvironment host

    def setup() {
        host = new HostTaskExecutionEnvironment(
                workspace, new VirtualClock(), TestChildEnvironment.hostAllowlist(), ProcessLaunch.grouped())
        host.materialize('gnomish/TASK-7', null)
    }

    def cleanup() {
        host.dispose()
    }

    /** A host environment whose guard probes are counted and whose denial read answers {@code denials}. */
    static class GuardedEnvironment implements TaskExecutionEnvironment {
        @Delegate
        TaskExecutionEnvironment delegate
        List<Denial> denials = []
        int probes
        RuntimeException probeFailure
        boolean disposed

        @Override
        void probeGuard() {
            probes++
            if (probeFailure != null) {
                throw probeFailure
            }
        }

        @Override
        DenialRead readDenials() {
            if (disposed) {
                throw new IllegalStateException('environment disposed')
            }
            new DenialRead(denials, Optional.empty())
        }
    }

    private ProgramStageExecutor executor(Path program, RoundEnvironmentSource rounds, Map<String, Object> settings = [:]) {
        def described = new DescribedProgram(ProgramFixtures.declaration([program.toString()], settings),
        ProgramFixtures.describe(), ['ACME_API_KEY'])
        new ProgramStageExecutor(PipelineLaw.ofContent(['instructions.md': 'build it\n']), described, rounds,
        ProgramFixtures.equipment(stderrParent))
    }

    private RoundEnvironmentSource.Round round(TaskExecutionEnvironment env) {
        Mock(RoundEnvironmentSource.Round) {
            environment() >> env
        }
    }

    // FR10, FR14: the round opens through the source, the guard is probed around the run, the source closes it
    def "FR10: a completed round is opened and closed through the round source, its guard probed twice, its denials attached"() {
        given:
        def environment = new GuardedEnvironment(delegate: host, denials: [EGRESS])
        def round = round(environment)
        def rounds = Mock(RoundEnvironmentSource)
        def program = StandIn.link(links.resolve('completed'), 'run-as')

        when:
        def result = executor(program, rounds).execute(ProgramFixtures.round(workspace))

        then:
        1 * rounds.openRound(_) >> round
        1 * round.closeRound()
        1 * round.readDecision() >> Optional.empty()
        0 * round.discard()
        result.status() instanceof ExecutionResult.Completed
        result.denials() == [EGRESS]
        result.usage().byModel().keySet() == ['acme-model-1'] as Set
        environment.probes == 2
    }

    // FR9 (task 7.1, obligation 4), FR4: the request carries the one merge of the settings, the model and the prior findings
    def "FR9: the request carries the declaration's settings under the stage's, the model, and the prior attempts' findings"() {
        given:
        def program = StandIn.recording(links, 'run-recorded')
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round(new GuardedEnvironment(delegate: host))
        }
        def stage = ProgramFixtures.stage('acme-model-1', [maxTurns: 9])
        def prior = new CheckResult(new CheckRef(0, UntrustedText.manifest('lint')),
                new Verdict.Fail([
                    new Finding('unused import', 'A.java:3', null)
                ]), Duration.ZERO)

        when:
        executor(program, rounds, [maxTurns: 3, style: 'strict'])
        .execute(ProgramFixtures.round(workspace, stage, 1, [prior]))
        def answerPath = (StandIn.log(program).toFile().text =~ /GNOMISH_ANSWER_FILE=\[([^\]]+)\]/)[0][1] as String
        def request = new String(host.readFile(answerPath + '.request', 1 << 20).get(), StandardCharsets.UTF_8)

        then:
        new ObjectMapper().readValue(request, Map).settings == [maxTurns: 9, style: 'strict', model: 'acme-model-1']
        request.contains('"feedback":[{"message":"unused import","location":"A.java:3"}]')
        request.contains('"bounds":{"startupReadMillis":30000,"silenceMillis":300000,"overallMillis":1800000}')
    }

    // FR10, NFR-R1: a dead guard discards the round, which is failed/infrastructure with the drained denials
    def "FR10: a round whose guard died is discarded and failed/infrastructure with the denials drained from it"() {
        given:
        def environment = new GuardedEnvironment(delegate: host, denials: [EGRESS],
        probeFailure: new IllegalStateException('egress guard is not running'))
        def round = round(environment)
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }

        when:
        def result = executor(StandIn.link(links.resolve('completed'), 'run-as'), rounds)
                .execute(ProgramFixtures.round(workspace))

        then:
        1 * round.discard()
        0 * round.closeRound()
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.reason().contains('egress guard is not running')
        result.denials() == [EGRESS]
    }

    // FR10, NFR-R1: the host discard disposes the environment, so the denials are drained before it
    def "FR10: a failed round's denials are drained before the round is discarded"() {
        given:
        def env = new GuardedEnvironment(delegate: host, denials: [EGRESS],
        probeFailure: new IllegalStateException('egress guard is not running'))
        def round = Mock(RoundEnvironmentSource.Round) {
            environment() >> env
        }
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }

        when:
        def result = executor(StandIn.link(links.resolve('completed'), 'run-as'), rounds)
                .execute(ProgramFixtures.round(workspace))

        then:
        1 * round.discard() >> { env.disposed = true }
        result.denials() == [EGRESS]
    }

    // FR13 of add-agent-executor's shape: an unreadable instructions file stops the round before any environment is opened
    def "FR10: unreadable instructions fail before the round source is asked for a round"() {
        given:
        def rounds = Mock(RoundEnvironmentSource)
        def described = new DescribedProgram(ProgramFixtures.declaration(['unused']), ProgramFixtures.describe(), [])
        def executor = new ProgramStageExecutor(PipelineLaw.ofContent([:]), described, rounds,
        ProgramFixtures.equipment(stderrParent, ProgramBounds.DEFAULTS))

        when:
        executor.execute(ProgramFixtures.round(workspace))

        then:
        thrown(RuntimeException)
        0 * rounds.openRound(_)
    }
}
