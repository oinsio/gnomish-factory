package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches
import com.github.oinsio.gnomish.adapter.git.GitTaskStore
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees
import com.github.oinsio.gnomish.adapter.git.MidRoundPushRounds
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.Logger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * M1, UX1 of wire-host-mid-round-push: the wired whole. A real git-mode host run over a local
 * bare remote, with the agent binary standing in for a gnome that commits mid-round through a
 * Bash tool: the commit lands on origin when the next progress event is observed — BEFORE the
 * round closes — and the healthy run stays silent on the operator plane (zero WARN/ERROR).
 *
 * <p>The gnome script proves the "before the round closes" half itself: after emitting the
 * post-commit progress event it polls the bare remote until the pushed tip appears, records what
 * it saw, and only then emits the round's result event. A recorded tip equal to the commit is
 * therefore an observation made strictly inside the round.
 */
class GitModeMidRoundPushSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    Path cloneDir
    Path bareRepo
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        bareRepo = initBareRepo(tempDir, 'origin.git')
        gitRunner.run(cloneDir, 'remote', 'add', 'origin', bareRepo.toString())
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    /**
     * The gnome: a stream-json-emitting agent stand-in that commits in its cwd (the task
     * worktree) between two tool events, then polls the bare remote for the pushed tip and
     * records the observation before closing the round with its result event.
     */
    private Path gnomeScript(Path observedRemoteTip, Path committedTip) {
        Path script = tempDir.resolve('gnome-agent.sh')
        Files.writeString(script, """#!/bin/sh
set -eu
echo '{"type":"system","subtype":"init","session_id":"fake-session-1","model":"claude-fake-main-1","cwd":"/workspace","tools":["Bash"]}'
echo '{"type":"assistant","session_id":"fake-session-1","message":{"id":"msg_1","model":"claude-fake-main-1","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"git commit"}}]}}'
echo 'gnome mid-round work' > gnome.txt
git add gnome.txt >/dev/null 2>&1
git -c user.email=g@b.c -c user.name=gnome commit -q -m 'gnome mid-round commit'
git rev-parse HEAD > '${committedTip}'
echo '{"type":"assistant","session_id":"fake-session-1","message":{"id":"msg_2","model":"claude-fake-main-1","content":[{"type":"tool_use","id":"toolu_2","name":"Bash","input":{"command":"true"}}]}}'
local_tip=\$(cat '${committedTip}')
i=0
remote_tip=none
while [ \$i -lt 150 ]; do
    remote_tip=\$(git --git-dir='${bareRepo}' rev-parse refs/heads/gnomish/PROJ-1 2>/dev/null || echo none)
    [ "\$remote_tip" = "\$local_tip" ] && break
    i=\$((i+1))
    sleep 0.1
done
printf '%s' "\$remote_tip" > '${observedRemoteTip}'
echo '{"type":"result","subtype":"success","session_id":"fake-session-1","result":"Stage complete.","usage":{"input_tokens":120,"output_tokens":45,"cache_creation_input_tokens":10,"cache_read_input_tokens":5},"modelUsage":{"claude-fake-main-1":{"inputTokens":120,"outputTokens":45,"cacheCreationInputTokens":10,"cacheReadInputTokens":5}}}'
""")
        script.toFile().setExecutable(true)
        script
    }

    /** The production TaskGit shape: real git backend plus the real mid-round push decoration. */
    private static TaskGit taskGit() {
        def runner = new GitProcessRunner()
        new TaskGit(
                new GitTaskStore(runner, ClaimEpochSource.NONE),
                new GitTaskBranches(runner, ClaimEpochSource.NONE),
                new GitTaskWorktrees(runner, ClaimEpochSource.NONE), { RoundEnvironmentSource rounds ->
                    new MidRoundPushRounds(rounds, runner)
                })
    }

    // M1: gnome commit mid-round -> next progress event -> the remote tip equals the new commit,
    // observed by the gnome itself before it closes the round.
    // UX1 (5.2): the same healthy run produces zero WARN/ERROR after startup.
    def "a gnome commit mid-round reaches origin before the round closes, silently"() {
        given:
        Path observedRemoteTip = tempDir.resolve('observed-remote-tip')
        Path committedTip = tempDir.resolve('committed-tip')
        def properties = testProperties(
                agentCliBinary: gnomeScript(observedRemoteTip, committedTip).toString(), agentCliEnvPassthrough: [])
        def output = new ByteArrayOutputStream()
        def runner = new GitModeRunner(
                newAssembly(null, new PrintStream(output, true, 'UTF-8'), properties), taskGit(), worktreesRoot)
        def operatorPlane = LogCaptureSupport.attach(Logger.ROOT_LOGGER_NAME, Level.WARN)

        when:
        def originalOut = System.out
        System.out = new PrintStream(output, true, 'UTF-8')
        try {
            runner.run(cloneDir, null,
                    new PipelineDefinition('1', new AutonomyLimits(3), [stage()]),
                    new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of()),
                    TaskState.atStageStart('build'), RunArguments.InteractiveMode.NONE)
        } finally {
            System.out = originalOut
        }

        then: 'the gnome observed its commit on the remote before emitting the round result (M1)'
        Files.exists(observedRemoteTip)
        Files.readString(observedRemoteTip) == Files.readString(committedTip).trim()

        and: 'the healthy run put nothing on the operator plane (UX1): zero WARN/ERROR'
        operatorPlane.list.findAll {
            it.level.isGreaterOrEqual(Level.WARN)
        }.empty

        cleanup:
        operatorPlane.detach()
    }
}
