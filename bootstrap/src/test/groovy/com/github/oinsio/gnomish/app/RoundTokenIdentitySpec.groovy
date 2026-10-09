package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.agent.ResumeVerificationStageExecutor
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Identity spec of design D10 of make-checkpoint-gate-durable (D7, row {@code RoundToken.of}): the
 * component specs each prove one link (mint, subject, parse, restore, carve-out); only a flow over a
 * bare origin and the real adapters proves the round token is one value through all of them.
 *
 * <p>The gnome is the fake agent's {@code decision-needed} scenario under the real {@link
 * CliStageExecutor} and {@link SandboxRoundEnvironmentSource}; the box is {@link LocalBoxEnvironment}
 * (real clone, in-box commits, harvest — no daemon). Every frozen state is pushed to origin first,
 * and the pickup is another instance's fresh clone of it.
 *
 * <p>FR13, FR15, M5 of make-checkpoint-gate-durable.
 */
class RoundTokenIdentitySpec extends Specification implements BareGitRepoFixture {

    static final String TASK = FakeAgentSupport.defaultTaskContext().taskId()
    static final String BRANCH = TaskIdSanitizer.branchName(TASK)
    static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path origin
    Path clone
    StageExecutor.Request request

    def setup() {
        clone = initWorkingRepo(tempDir, 'clone')
        commit(clone, 'seed.txt', 'seed')
        // Converged on purpose: the subject is the round's identity, not base resolution.
        origin = addConvergedOrigin(clone, tempDir)
        repositoryOver(clone).createTask(FakeAgentSupport.defaultTaskContext(), TaskStart.commit(clone, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('build'))
        request = FakeAgentSupport.requestFor(Files.createDirectories(tempDir.resolve('workspace')))
    }

    // FR13, FR15, M5: the request's file name, the snapshot subject and the open tip are one token;
    //     after the answer the next round is handed a path the tip does not hold
    def "live: a round that asked names one token three times, and the answered round's path is not reused"() {
        given: 'a round source over a box, sharing the run\'s round cell with the persistence'
        def rounds = new CurrentRound()
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('box')))
        def source = roundSource(box, rounds)
        String openedOn = tip(clone)

        when: 'the gnome asks, the round closes, and the engine persists it'
        def result = agentOver(source).execute(request)
        String snapshot = tip(clone)
        persistRound(box, clone, rounds)
        push(clone)

        then: 'the open tip, the snapshot subject and the request\'s file name are one value'
        result instanceof ExecutionResult.DecisionNeeded
        gitOutput(clone, 'rev-parse', snapshot + '^') == openedOn
        subjectOf(origin, snapshot) == "gnomish: snapshot build#0 ${openedOn}".toString()
        requestsIn(origin, snapshot) == [requestPath(openedOn)]

        when: 'the operator answers — the stage restarts at attempt 0, the asking round\'s key'
        def repository = repositoryOver(clone)
        repository.recordOutcome(TASK, new TaskOutcome.Escalated(TaskState.atStageStart('build'),
                new EscalationReport.DecisionNeeded(result.question(), result.options())), TrackerWrite.OWED)
        repository.appendDecision(TASK, new Decision('refactor', null, null, null), TaskState.atStageStart('build'))
        push(clone)
        String answered = tip(origin)
        def next = source.openRound(request)

        then: 'the next round is handed its own token\'s path, which the tip does not hold, and reads nothing'
        next.decisionFilePath().toString() == requestPath(answered)
        answered != openedOn
        gitExitCode(origin, 'cat-file', '-e', "${answered}:${next.decisionFilePath()}") != 0
        next.readDecision().isEmpty()
    }

    // FR15, M5: a kill between the snapshot and the state commit — another instance's pickup
    //     restores the round from the snapshot's record, and its state commit lands on it
    def "resumed: the pickup's state commit lands, judged by the token the snapshot recorded"() {
        given: 'a round that asked, its snapshot pushed, killed before its state commit'
        def box = new LocalBoxEnvironment(clone, Files.createDirectories(tempDir.resolve('box')))
        String openedOn = tip(clone)
        agentOver(roundSource(box, new CurrentRound())).execute(request)
        String snapshot = tip(clone)
        push(clone)

        and: 'another instance clones origin; its run has a fresh cell and a fresh box'
        Path pickupClone = seedClone(tempDir, origin.toString(), tempDir.resolve('pickup'))
        // The task branch as a local ref, not checked out — harvest fast-forwards it, as in a factory clone.
        gitOutput(pickupClone, 'branch', BRANCH, "refs/remotes/origin/${BRANCH}")
        def rounds = new CurrentRound()
        def pickupBox = new LocalBoxEnvironment(pickupClone, Files.createDirectories(tempDir.resolve('pickup-box')))
        pickupBox.materialize(BRANCH, null)
        def pending = new SnapshotTipCheck(runner, pickupClone).inspect(TASK).get()
        def noAgent = { StageExecutor.Request r ->
            throw new AssertionError('a pending verification runs no agent round')
        } as StageExecutor

        when: 'the pickup re-verifies without an agent round and persists, as the engine does'
        def result = new ResumeVerificationStageExecutor(noAgent, rounds, pending).execute(request)
        persistRound(pickupBox, pickupClone, rounds)

        then: 'the request is re-raised from the snapshot, and the state commit landed on the snapshot itself'
        result instanceof ExecutionResult.DecisionNeeded
        String stateCommit = tip(pickupClone)
        subjectOf(pickupClone, stateCommit) == 'gnomish: round build#0'
        gitOutput(pickupClone, 'rev-parse', stateCommit + '^') == snapshot

        and: 'the round it judged is the recorded one: the open tip is the token, the snapshot the commit'
        pending.token().commit() == openedOn
        rounds.closed().token() == pending.token()
        rounds.closed().attemptCommit() == snapshot
        // the boundary passed with the request carved out under that token, still on the tip
        requestsIn(pickupClone, stateCommit) == [requestPath(openedOn)]
    }

    private SandboxRoundEnvironmentSource roundSource(LocalBoxEnvironment box, CurrentRound rounds) {
        def lease = new EnvironmentLease({
            -> box
        }, BRANCH, [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [request.stage()])
        ])
        new SandboxRoundEnvironmentSource(lease, runner, clone, TASK, rounds, new VirtualClock())
    }

    private static StageExecutor agentOver(SandboxRoundEnvironmentSource source) {
        // Ahead of the wall clock the local box stamps process starts with, so wall time is positive.
        new CliStageExecutor(FakeAgentSupport.propertiesFor('decision-needed'), new VirtualClock(Instant.now().plusSeconds(3600)),
                { e -> } as AgentProgressListener, LAW, source)
    }

    private void persistRound(LocalBoxEnvironment box, Path factoryClone, CurrentRound rounds) {
        new EnvironmentAttemptPersistence(box, runner, factoryClone, objectsOf(factoryClone), TASK, rounds, ClaimEpochSource.NONE)
                .persist(TASK, TaskState.atStageStart('build'), new ToolTrace(new AttemptKey(TASK, 'build', 0), []))
    }

    private GitObjectsTaskRepository repositoryOver(Path factoryClone) {
        new GitObjectsTaskRepository(objectsOf(factoryClone), ClaimEpochSource.NONE, DenialCursorSource.NONE)
    }

    private GitObjects objectsOf(Path factoryClone) {
        GitObjects.open(factoryClone.resolve('.git'), Files.createTempDirectory(tempDir, 'index'))
    }

    private static String requestPath(String token) {
        ".gnomish-task/decisions/build-a0-${token}.json".toString()
    }

    private String tip(Path repo) {
        gitOutput(repo, 'rev-parse', 'refs/heads/' + BRANCH)
    }

    private void push(Path repo) {
        assert runner.run(repo, 'push', 'origin', "refs/heads/${BRANCH}:refs/heads/${BRANCH}").exitCode() == 0
    }

    private List<String> requestsIn(Path repo, String commit) {
        gitOutput(repo, 'ls-tree', '-r', '--name-only', commit, '--', '.gnomish-task/decisions/').readLines()
    }
}
