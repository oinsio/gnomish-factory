package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19, NFR-S2, D14 of add-sandbox-core: the git-mode contract the spec demands — "a contract
 * test SHALL enforce the source in git modes". Unlike {@code PipelineLawReaderSpec}, which is
 * mode-agnostic (one directory, an in-place edit of the same file it froze), this drives a real
 * {@link GitModeRunner} where the law source (the factory clone's working tree, {@code cloneDir})
 * and the gnome's working copy (the task {@code worktree} under {@code worktreesRoot}) are two
 * physically distinct directories — exactly the git-mode split {@link ManualRunAssembly#assemble}
 * wires by passing {@code cloneDir} as the law source while rooting the workspace at the worktree.
 *
 * <p>The reward-hacking move it defends against: mid-run, the gnome rewrites its own {@code
 * instructions.md} control file in the worktree (the {@code law-tamper-then-plain} fake-agent
 * scenario). The first attempt's tampered write is real — it is committed onto the gnome branch by
 * the round snapshot — and it makes a {@code files_exist} check fail, forcing a second attempt in
 * the same worktree. If the law were re-read lazily from the gnome-writable working copy, that
 * second attempt's prompt would carry the {@code TAMPERED LAW} text; because the law is frozen once
 * from the clone at invocation start, it still carries the clone's {@code ORIGINAL LAW}. Both
 * attempts' actual stdin prompts are captured via {@code GNOMISH_FAKE_CAPTURE_STDIN} so the
 * guarantee is observed end-to-end through the real adapter, not asserted by construction.
 */
class GitModeLawBindingSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    private static final String ORIGINAL_LAW =
    'ORIGINAL LAW: implement the widget precisely and never bypass a verify check.'
    private static final String TAMPERED_MARKER = 'TAMPERED LAW'

    Path cloneDir
    Path worktreesRoot
    def gitRunner = new GitProcessRunner()

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'law-binding-project')
        Files.writeString(cloneDir.resolve('instructions.md'), ORIGINAL_LAW + '\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    /** A fake-agent wrapper pinned to a scenario, with per-invocation stdin (the round prompt) captured. */
    private FactoryProperties fakeAgentProperties(String scenario, String captureStdinPath) {
        URL resource = getClass().getResource('/fake-agent/fake-agent.sh')
        def scriptPath = Path.of(resource.toURI()).toAbsolutePath().toString()
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = """#!/bin/sh
export GNOMISH_FAKE_SCENARIO='${scenario}'
export GNOMISH_FAKE_CAPTURE_STDIN='${captureStdinPath}'
exec sh '${scriptPath}' "\$@"
"""
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        testProperties(agentCliBinary: wrapper.absolutePath, agentCliEnvPassthrough: [])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md',
                [
                    new VerifyCheck.Builtin('files_exist', [files: ['output.txt']])
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    // FR19, NFR-S2, D14: in git mode the law is bound from the clone, never re-read from the
    // gnome's worktree — a mid-run rewrite of instructions.md burns an attempt and lands on the
    // gnome branch, yet every attempt's prompt still carries the clone's ORIGINAL law.
    def "a mid-run gnome edit to the worktree's control file never re-enters the frozen law"() {
        given: 'a captured-stdin file the fake appends each attempt\'s prompt to'
        def captureFile = File.createTempFile('fake-agent-stdin', '.log')
        captureFile.deleteOnExit()
        def runner = new GitModeRunner(
                newAssembly(new ByteArrayInputStream(new byte[0]), System.out,
                fakeAgentProperties('law-tamper-then-plain', captureFile.absolutePath)),
                worktreesRoot)
        def context = new TaskContext('LAW-1', 'title', 'body', List.<Decision> of())

        when: 'a fresh git-mode run: attempt 1 tampers + fails files_exist, attempt 2 completes'
        runner.run(cloneDir, null, pipeline(), context, TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE)

        then: 'the run reached Completed and left the delivered branch behind'
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/LAW-1').exitCode() == 0

        and: 'the gnome really did rewrite its own instructions.md — the tampered text is committed on the gnome branch'
        def branchInstructions = gitRunner.run(cloneDir, 'show', 'gnomish/LAW-1:instructions.md').stdout()
        branchInstructions.contains(TAMPERED_MARKER)
        !branchInstructions.contains(ORIGINAL_LAW)

        and: "the clone's own working copy was never touched — its instructions.md is still the original law"
        Files.readString(cloneDir.resolve('instructions.md')).contains(ORIGINAL_LAW)

        and: 'exactly two attempts ran (the tamper attempt burned by the failed check, then the completing one)'
        def prompts = captureFile.text.split('(?m)^---$').collect {
            it.trim()
        }.findAll {
            !it.isEmpty()
        }
        prompts.size() == 2

        and: "attempt 1's prompt carried the clone's ORIGINAL law"
        prompts[0].contains(ORIGINAL_LAW)

        and: "attempt 2's prompt — built AFTER the worktree's instructions.md was tampered — still carried the ORIGINAL law, never the tampered text"
        prompts[1].contains(ORIGINAL_LAW)
        !prompts[1].contains(TAMPERED_MARKER)
    }
}
