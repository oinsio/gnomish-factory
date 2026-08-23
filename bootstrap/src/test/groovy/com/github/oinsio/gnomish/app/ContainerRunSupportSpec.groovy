package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.PushBestEffortAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.state.StateEgressCursorDto
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5, FR6, FR21, FR25, D19 of add-sandbox-core: the per-run container support bundle's terminal
 * and lifecycle operations, daemon-free over a scripted fake docker CLI — the aborted boundary
 * records on the branch and pushes best-effort, keep semantics dispose the fresh judge box while
 * stopping (not removing) the round box, {@code --discard-work} disposal tears down the round
 * key's objects, and a disappeared branch is reported honestly.
 */
class ContainerRunSupportSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    static final String KEY = 't-1'

    @TempDir
    Path tempDir

    def docker = new ScriptedSandboxDocker()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    Path cloneDir
    Path origin

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'seed\n')
        commitAll(cloneDir)
        origin = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', origin.toString())
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private ContainerRunSupport support(String taskId = 'T-1', String key = KEY) {
        def environments = docker.environments(key, cloneDir, sandbox, tempDir.resolve('guard'))
        new ContainerRunSupport(
                new GitProcessRunner(), cloneDir, taskId,
                environments, [
                    new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
                ], SandboxLifecyclePass.NONE)
    }

    private static void createTask(ContainerRunSupport support, String taskId = 'T-1') {
        support.taskRepository().createTask(new TaskContext(taskId, 'title', 'body', List.<Decision> of()), 'HEAD')
    }

    // D19: the aborted boundary commits the outcome on the branch tip and pushes it best-effort.
    def "recordAborted records the outcome on the branch and pushes it to origin"() {
        given:
        def support = support()
        createTask(support)

        when:
        support.recordAborted(new TaskOutcome.Aborted(
                        TaskState.atStageStart('build'), new AttemptKey('T-1', 'build', 0), 'durability broke'))

        then: 'the branch tip carries the aborted outcome'
        gitOutput(cloneDir, 'show', 'gnomish/T-1:.gnomish-task/task.json').contains('"aborted"')

        and: 'the best-effort push delivered exactly that tip to origin'
        gitOutput(origin, 'rev-parse', 'refs/heads/gnomish/T-1') == gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1')
    }

    /**
     * Installs a pre-receive hook on the bare origin that rejects the FIRST push it sees and
     * accepts every one after it — so a terminal boundary's own recording push fails and only the
     * touchpoint reconciliation behind it can deliver the tip.
     */
    private void rejectFirstPush() {
        def hook = origin.resolve('hooks').resolve('pre-receive').toFile()
        hook.parentFile.mkdirs()
        hook.text = '''#!/bin/sh
marker="$(git rev-parse --git-dir)/first-push-seen"
if [ ! -f "$marker" ]; then touch "$marker"; echo "transient" >&2; exit 1; fi
exit 0
'''
        hook.setExecutable(true)
    }

    // FR3 of fix-lifecycle-push: the terminal boundary closes with the touchpoint reconciliation,
    // the level safety net behind the outcome's own recording push. With that push rejected, the
    // reconciliation is the only thing that can still get the aborted tip onto origin.
    def "the aborted terminal boundary reconciles origin when the recording push failed"() {
        given:
        def support = support()
        createTask(support)
        rejectFirstPush()

        when: 'the whole boundary runs, as both drives run it: record, then keep the box stopped'
        support.recordAborted(new TaskOutcome.Aborted(
                        TaskState.atStageStart('build'), new AttemptKey('T-1', 'build', 0), 'durability broke'))
        support.keepStopped()

        then: 'origin still ends at the local tip — delivered by the reconciliation, not the push'
        gitOutput(origin, 'rev-parse', 'refs/heads/gnomish/T-1') == gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1')
    }

    // FR3 of fix-lifecycle-push: a container park (Escalated/Paused) records no lifecycle commit of
    // its own, so it has NO recording push behind it — the keep boundary's reconciliation is the
    // only thing that can still deliver the tip the human is about to be pointed at.
    def "the park terminal boundary reconciles origin with no recording push of its own behind it"() {
        given: 'a task branch whose creation push was rejected, so origin has never seen the branch'
        rejectFirstPush()
        def support = support()
        createTask(support)
        assert gitOutput(cloneDir, 'ls-remote', 'origin', 'refs/heads/gnomish/T-1').isEmpty()

        when: 'the run parks: no outcome is recorded in container mode, the box is just kept stopped'
        support.keepStopped()

        then: 'the branch tip reached origin through the terminal-boundary reconciliation'
        gitOutput(origin, 'rev-parse', 'refs/heads/gnomish/T-1') == gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1')
    }

    // FR3 of fix-lifecycle-push: the same safety net on the Completed boundary.
    def "completeAndDispose's terminal boundary reconciles origin when the recording push failed"() {
        given:
        def support = support()
        createTask(support)
        rejectFirstPush()

        when:
        support.completeAndDispose(TaskState.atStageStart('build'))

        then: 'the cleanup tip reached origin through the reconciliation'
        gitOutput(origin, 'rev-parse', 'refs/heads/gnomish/T-1') == gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1')
    }

    // FR6, D9: keep semantics — the fresh judge box holds nothing durable and is disposed; the
    // round box is stopped (never removed), keeping volume and network for salvage and resume.
    def "keepStopped disposes the materialized judge box and stops the round box"() {
        given: 'a judge box materialized for an attempt commit (scripted docker, self-check passes)'
        def support = support()
        createTask(support)
        def attemptCommit = new AttemptCommitRef()
        attemptCommit.record(gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1').trim())
        support.pieces(null).judgeEnvironments().environmentFor(new RecordedAttemptCommitWorkspace(attemptCommit))

        when:
        support.keepStopped()

        then: 'the judge box (role suffix -j) is removed'
        docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + KEY + '-j'
        ])

        and: 'the round box is stopped, not removed'
        docker.runs.contains(['stop', 'gnomish-box-' + KEY])
        !docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + KEY
        ])
    }

    // FR6: --discard-work disposal removes container, volume, and network of the round key.
    def "disposeExistingEnvironment tears down the round key's docker objects"() {
        given:
        def support = support()

        when:
        support.disposeExistingEnvironment()

        then:
        docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + KEY
        ])
        docker.runs.contains([
            'volume',
            'rm',
            'gnomish-vol-' + KEY
        ])
        docker.runs.contains([
            'network',
            'rm',
            'gnomish-net-' + KEY
        ])
    }

    // FR17: readFinalState reads back the exact state.json content committed on the branch tip —
    // asserted on the real position, not merely "did not throw" (M4 mutation-gate coverage).
    def "readFinalState reads back the exact state.json content committed on the branch tip"() {
        given: 'a task branch carrying a committed state.json alongside task.json'
        def support = support()
        createTask(support)
        commitState(StateJsonMapper.toDto(TaskState.atStageStart('build')))

        when:
        def state = support.readFinalState()

        then:
        (state.position() as Position.AtStage).name() == 'build'
    }

    // FR5 of fix-denial-report-attachment: the guard container outlives the process that made it,
    // so a resume reads the cursor its last attempt committed and hands it to the environments —
    // without it the first read after resume replays the container's whole surviving log
    def "FR5: restoreDenialCursor hands the branch tip's committed cursor to the environments"() {
        given: 'a task branch whose state.json records a cursor naming the live guard container'
        def support = support()
        createTask(support)
        commitState(StateJsonMapper.toDto(
                        TaskState.atStageStart('build'),
                        new StateEgressCursorDto('sha256:guard-container', '2026-08-19T10:00:00.000000001Z')))

        when:
        support.restoreDenialCursor()
        support.environments.roundEnvironment().denialFindings()

        then: 'the round box reads its guard log from the committed position, not from the start'
        docker.runs.last() == guardLogsArgv('2026-08-19T10:00:00.000000001Z')
    }

    def "FR5: a branch with no committed cursor leaves the environments reading from the start"() {
        given: 'a task branch whose state.json predates the cursor field'
        def support = support()
        createTask(support)
        commitState(StateJsonMapper.toDto(TaskState.atStageStart('build')))

        when:
        support.restoreDenialCursor()
        support.environments.roundEnvironment().denialFindings()

        then:
        docker.runs.last() == guardLogsArgv(null)
    }

    /** The guard log read argv, spelled out here: {@code GuardCommands} is package-private to its adapter. */
    private static List<String> guardLogsArgv(String since) {
        def argv = [
            'logs',
            '--tail',
            '1000',
            '--timestamps'
        ]
        if (since != null) {
            argv += ['--since', since]
        }
        argv + ['gnomish-guard-' + KEY]
    }

    def "FR5: a branch with no state.json at all is a no-op, never a failure"() {
        given: 'a task branch that never persisted a round'
        def support = support()
        createTask(support)

        when:
        support.restoreDenialCursor()

        then:
        noExceptionThrown()
    }

    /** Commits {@code state.json} on the task branch, as a finished round would have. */
    private void commitState(dto) {
        def json = TaskStateJson.mapper().writeValueAsString(dto)
        def originalBranch = gitOutput(cloneDir, 'rev-parse', '--abbrev-ref', 'HEAD').trim()
        gitOutput(cloneDir, 'checkout', 'gnomish/T-1')
        Files.writeString(cloneDir.resolve('.gnomish-task/state.json'), json)
        gitOutput(cloneDir, 'add', '.gnomish-task/state.json')
        gitOutput(cloneDir, '-c', 'user.email=g@b.c', '-c', 'user.name=g', 'commit', '-m', 'state')
        gitOutput(cloneDir, 'checkout', originalBranch)
    }

    // NFR-R1: a branch that disappeared mid-run is an honest IllegalStateException naming it,
    // never a bare NullPointerException from the ref lookup.
    def "reading the branch tip of a disappeared branch names the branch honestly"() {
        given: 'a support whose task branch was never created'
        def support = support('T-GHOST', 't-ghost')

        when:
        support.readTaskJson()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('gnomish/T-GHOST')
        e.message.contains('disappeared')
    }

    // FR3: the task branch is the sanitized taskId under gnomish/, the actual computed value —
    // not merely a non-blank string.
    def "branch returns the sanitized gnomish/<taskId> branch name"() {
        expect:
        support('T-1').branch() == 'gnomish/T-1'
    }

    // D19: completeAndDispose is proven — via its real, observable effects, since its three
    // collaborators are private fields with no seam for mock injection — to invoke all three of
    // FreshJudgeEnvironments::disposeCurrent (the judge box is force-removed), EnvironmentLease::
    // dispose (the round box is force-removed too, not merely stopped — unlike keepStopped), and
    // BranchPush::pushBestEffort (the outcome commit reaches origin).
    def "completeAndDispose disposes the judge box, disposes the round box, and pushes the outcome to origin"() {
        given: 'a round box and a judge box both materialized'
        def support = support()
        createTask(support)
        support.lease().environmentFor('build')
        def attemptCommit = new AttemptCommitRef()
        attemptCommit.record(gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1').trim())
        support.pieces(null).judgeEnvironments().environmentFor(new RecordedAttemptCommitWorkspace(attemptCommit))

        when:
        support.completeAndDispose(TaskState.atStageStart('build'))

        then: 'the judge box (role suffix -j) was force-removed (FreshJudgeEnvironments::disposeCurrent)'
        docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + KEY + '-j'
        ])

        and: 'the round box was force-removed too — disposed, not merely stopped (EnvironmentLease::dispose)'
        docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + KEY
        ])
        docker.runs.contains([
            'volume',
            'rm',
            'gnomish-vol-' + KEY
        ])
        docker.runs.contains([
            'network',
            'rm',
            'gnomish-net-' + KEY
        ])

        and: 'the completed outcome was recorded — one commit below the tip, since Completed adds a cleanup commit removing .gnomish-task/ (D19)'
        gitOutput(cloneDir, 'show', 'gnomish/T-1~1:.gnomish-task/task.json').contains('"completed"')

        and: 'the completed outcome reached origin (BranchPush::pushBestEffort)'
        gitOutput(origin, 'rev-parse', 'refs/heads/gnomish/T-1') == gitOutput(cloneDir, 'rev-parse', 'gnomish/T-1')
    }

    // FR3 of add-sandbox-core; FR17, D11 of add-plugin-architecture: create() scrubs whatever
    // credential names the configured check providers declared through the SPI — handed down by the
    // composition root, never named here. Observed via ContainerEnvironments#scrubsCredential, a
    // testing seam over the composed allowlist (FR9), since neither branch is otherwise observable
    // without materializing a real environment.
    def "create scrubs exactly the check credential names the composition root resolved"() {
        given:
        def segments = [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]

        when:
        def configuredSupport = ContainerRunSupport.create(
                cloneDir, 'T-CFG', segments, sandbox, [
                    GithubCheckClientFactory.TOKEN_ENV_VAR
                ], [], OwnershipMode.MANUAL)
        def unconfiguredSupport =
                ContainerRunSupport.create(cloneDir, 'T-UNCFG', segments, sandbox, [], [],
                OwnershipMode.MANUAL)

        then:
        configuredSupport.environments.scrubsCredential(GithubCheckClientFactory.TOKEN_ENV_VAR)
        !unconfiguredSupport.environments.scrubsCredential(GithubCheckClientFactory.TOKEN_ENV_VAR)
    }

    // FR12: lease() returns the run's real EnvironmentLease — not null — and it is the very
    // instance environmentFor materializes through, proven by identity across repeated calls.
    def "lease returns the same real EnvironmentLease every call"() {
        given:
        def support = support()

        expect:
        support.lease() != null
        support.lease().is(support.lease())
    }

    // FR5, FR21, FR22: persistence() returns the real strict-persistence-plus-best-effort-push
    // wrapper, not null.
    def "persistence returns the sandboxed persistence wrapped with best-effort push"() {
        expect:
        support().persistence() instanceof PushBestEffortAttemptPersistence
    }

    // D15: workspace() returns the real attempt-commit workspace, not null — the engine workspace
    // of a sandboxed run is never a host path.
    def "workspace returns a real RecordedAttemptCommitWorkspace"() {
        expect:
        support().workspace() instanceof RecordedAttemptCommitWorkspace
    }

    // FR6: salvage() is wired to the run's real lease, not a disconnected stub — proven by
    // actually reaching the leased environment's in-box status probe after the stage environment
    // was materialized (a null or mis-wired salvage would either NPE or throw the lease's own
    // "no environment leased yet" IllegalStateException instead).
    def "salvage operates on the currently leased environment"() {
        given:
        def support = support()
        createTask(support)
        support.lease().environmentFor('build')

        when:
        def hasLeftovers = support.salvage().hasLeftovers()

        then:
        noExceptionThrown()
        hasLeftovers
        docker.starts.any { it.contains('git status --porcelain') }
    }
}
