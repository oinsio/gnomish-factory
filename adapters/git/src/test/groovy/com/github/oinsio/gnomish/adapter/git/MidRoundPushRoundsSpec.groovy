package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, NFR-P1 of wire-host-mid-round-push (design D1): {@link MidRoundPushRounds}
 * decorates the host round source so each round's listener is a fresh {@link
 * MidRoundPushListener} built from the request's own facts, every other {@code Round} method
 * passes through untouched, and the wiring itself adds no git invocations beyond the
 * listener's documented per-event {@code rev-parse}.
 */
class MidRoundPushRoundsSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path repo
    Path bareRepo

    def toolEvent = new AgentProgressEvent.ToolStarted('Bash')

    def setup() {
        repo = initWorkingRepo(tempDir)
        new File(repo.toFile(), 'a.txt').text = 'first'
        runner.run(repo, 'add', 'a.txt')
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(repo, 'checkout', '-q', '-b', 'gnomish/PROJ-1')

        bareRepo = initBareRepo(tempDir, 'origin.git')
        runner.run(repo, 'remote', 'add', 'origin', bareRepo.toString())
    }

    private StageExecutor.Request request(int attempt = 0) {
        def stage = new StageDefinition(
                'implement', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('PROJ-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(repo), attempt, [])
    }

    private void gnomeCommit(String fileName = 'gnome.txt') {
        new File(repo.toFile(), fileName).text = 'gnome work'
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'gnome commit')
    }

    // FR1: the decorated round's listener notices a gnome commit between two progress events
    // and pushes the task branch best-effort — the tip lands on origin before the round closes.
    def "a commit between two progress events is pushed by the round's listener"() {
        given:
        def source = new MidRoundPushRounds(new PassThroughRounds(), runner)
        def round = source.openRound(request())

        when:
        round.roundListener().onProgress(toolEvent)
        gnomeCommit()
        round.roundListener().onProgress(toolEvent)

        then:
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').stdout().trim() ==
                runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    // FR1: one fresh listener per round (the listener's documented lifecycle): each openRound
    // builds its own baseline, so a commit landing before the next round's open is that round's
    // starting tip, not a movement to push.
    def "each round gets a fresh listener with its own baseline"() {
        given:
        def source = new MidRoundPushRounds(new PassThroughRounds(), runner)
        source.openRound(request(0))
        gnomeCommit()

        when: 'a second round opens after the commit and observes an event'
        def second = source.openRound(request(1))
        second.roundListener().onProgress(toolEvent)

        then: 'the commit predates the second round\'s baseline — nothing is pushed'
        runner.run(bareRepo, 'rev-parse', 'gnomish/PROJ-1').exitCode() != 0
    }

    // FR2: every other Round method passes through to the delegate untouched.
    def "delegated methods pass through to the host round"() {
        given:
        def delegate = new PassThroughRounds()
        def source = new MidRoundPushRounds(delegate, runner)

        when:
        def round = source.openRound(request())
        round.closeRound()
        round.discard()

        then:
        delegate.round.environment.is(round.environment())
        round.decisionFilePath() == Path.of('decision.json')
        round.decisionEnvFragment() == [GNOMISH_DECISION_FILE: 'decision.json']
        round.readDecision() == Optional.of('decision-content')
        delegate.round.closed
        delegate.round.discarded
    }

    // NFR-P1: cost per tool event is the listener's one rev-parse — the wiring adds no git
    // invocations of its own. openRound reads the baseline once; each stationary event adds
    // exactly one invocation.
    def "the wiring adds no git invocations beyond the listener's per-event rev-parse"() {
        given: 'a git wrapper that logs every invocation before delegating to the real binary'
        Path invocationLog = tempDir.resolve('git-invocations.log')
        Path countingGit = tempDir.resolve('counting-git.sh')
        Files.writeString(countingGit, "#!/bin/sh\necho \"\$@\" >> '${invocationLog}'\nexec git \"\$@\"\n")
        Files.setPosixFilePermissions(countingGit, PosixFilePermissions.fromString('rwxr-xr-x'))
        def countingRunner = new GitProcessRunner(countingGit.toString())
        def source = new MidRoundPushRounds(new PassThroughRounds(), countingRunner)

        when: 'a round opens (baseline read) and two stationary events arrive'
        def round = source.openRound(request())
        int afterOpen = invocationCount(invocationLog)
        round.roundListener().onProgress(toolEvent)
        round.roundListener().onProgress(toolEvent)

        then:
        afterOpen == 1
        invocationCount(invocationLog) == 3
    }

    // NFR-O1 (design D4): the suppressor is shared across the decorator's rounds — a tip that
    // cannot be resolved is one fault whether it spans polls of one round or rounds of one task,
    // so the second round's failure is a suppressed repeat, not a fresh WARN edge.
    def "a tip-resolution failure spanning two rounds logs one WARN edge, not one per round"() {
        given: 'a workspace that is not a git repository, so every rev-parse fails'
        Path notARepo = Files.createDirectories(tempDir.resolve('not-a-repo'))
        def source = new MidRoundPushRounds(new PassThroughRounds(), runner)
        def logs = LogCaptureSupport.attach(MidRoundPushListener, Level.DEBUG)

        when: 'two rounds open over the broken workspace and each observes one event'
        def brokenRequest = { int attempt ->
            def stage = new StageDefinition(
            'implement', 'purpose', [], [],
            new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
            'instructions.md', [],
            new AutonomyLimits(3), AdvancementMode.AUTO)
            new StageExecutor.Request(
                    new TaskContext('PROJ-1', 'title', 'body', []),
                    stage, new DirectoryWorkspace(notARepo), attempt, [])
        }
        source.openRound(brokenRequest(0)).roundListener().onProgress(toolEvent)
        source.openRound(brokenRequest(1)).roundListener().onProgress(toolEvent)

        then: 'one WARN edge for the whole streak; the later polls are DEBUG repeats'
        def warnings = logs.list.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.startsWith(OperatorEvent.MID_ROUND_POLL_SKIPPED.head())
        warnings[0].formattedMessage.contains('taskId=PROJ-1')
        logs.list.findAll { it.level == Level.DEBUG }.size() >= 1
    }

    // NFR-R1: the wiring preserves the listener contract — a failing push inside onProgress
    // never throws into the live loop and never changes the round's outcome; the fault is one
    // WARN and the delegated round methods keep working.
    def "a failing push inside onProgress never throws out of the round"() {
        given: 'origin points nowhere, so the triggered push fails'
        runner.run(repo, 'remote', 'set-url', 'origin', tempDir.resolve('no-such-remote.git').toString())
        def delegate = new PassThroughRounds()
        def source = new MidRoundPushRounds(delegate, runner)
        def round = source.openRound(request())
        def logs = LogCaptureSupport.attach(BestEffortPush, Level.DEBUG)

        when: 'a gnome commit moves the tip and the next event triggers the failing push'
        round.roundListener().onProgress(toolEvent)
        gnomeCommit()
        round.roundListener().onProgress(toolEvent)
        round.closeRound()

        then: 'nothing thrown, the round closed normally, and the fault is the push WARN'
        noExceptionThrown()
        delegate.round.closed
        logs.list.findAll { it.level == Level.WARN }*.formattedMessage
        .any { it.startsWith(OperatorEvent.PUSH_FAILED.head()) }
    }

    // NFR-R1: a failing rev-parse skips the observation and never throws — the round closes
    // and reads its decision exactly as an undisturbed one does.
    def "a failing rev-parse inside onProgress never throws and leaves the round usable"() {
        given: 'a workspace that is not a git repository'
        Path notARepo = Files.createDirectories(tempDir.resolve('broken'))
        def delegate = new PassThroughRounds()
        def source = new MidRoundPushRounds(delegate, runner)
        def logs = LogCaptureSupport.attach(MidRoundPushListener, Level.DEBUG)
        def stage = new StageDefinition(
                'implement', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        def round = source.openRound(new StageExecutor.Request(
                        new TaskContext('PROJ-1', 'title', 'body', []),
                        stage, new DirectoryWorkspace(notARepo), 0, []))

        when:
        round.roundListener().onProgress(toolEvent)
        round.closeRound()

        then:
        noExceptionThrown()
        delegate.round.closed
        round.readDecision() == Optional.of('decision-content')
        logs.list.findAll { it.level == Level.WARN }*.formattedMessage
        .any {
            it.startsWith(OperatorEvent.MID_ROUND_POLL_SKIPPED.head())
        }
    }

    private static int invocationCount(Path log) {
        Files.exists(log) ? Files.readAllLines(log).size() : 0
    }

    /** A recording delegate whose rounds keep the seam's default no-op listener. */
    static class PassThroughRounds implements RoundEnvironmentSource {

        FakeRound round

        @Override
        Round openRound(StageExecutor.Request request) {
            round = new FakeRound()
            round
        }

        static class FakeRound implements Round {

            def environment = [:] as TaskExecutionEnvironment
            boolean closed
            boolean discarded

            @Override
            TaskExecutionEnvironment environment() {
                environment
            }

            @Override
            Path decisionFilePath() {
                Path.of('decision.json')
            }

            @Override
            Map<String, String> decisionEnvFragment() {
                [GNOMISH_DECISION_FILE: 'decision.json']
            }

            @Override
            void closeRound() {
                closed = true
            }

            @Override
            Optional<String> readDecision() {
                Optional.of('decision-content')
            }

            @Override
            void discard() {
                discarded = true
            }
        }
    }
}
