package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.boot.DefaultApplicationArguments

/**
 * Shared project/pipeline fixture and {@link TakeCommand} factory for {@link
 * TakeLifecycleEscalateResumeSpecBase} (task 6.2, M3, NFR-R3): a single-stage, attempt-limit-1
 * pipeline whose {@code files_exist} check fails deterministically (forcing an {@code
 * AttemptsExhausted} escalation on the first attempt), plus {@link #newCommand}, which builds a
 * brand-new {@link TakeCommand}/{@link ManualRunAssembly}/{@link FactoryProperties} trio sharing
 * no field or object with any other command built by a prior call (NFR-R3) — only the shared
 * {@link Tracker}, {@link TrackerAdapterFactory}, and {@code worktreesRoot} fields cross between
 * instances, exactly as two real factory processes on one machine would share the tracker service
 * and the machine-local {@code ~/.gnomish/worktrees} convention.
 *
 * <p>Split out of the spec base purely to respect the file-size guidance
 * (`.claude/rules/process-invariants.md`) — a plain trait, not a reusable port abstraction.
 *
 * <p>Implements FR9, FR11, M3, NFR-R3 of add-tracker-port.
 */
trait TwoInstanceTakeFixture implements BareGitRepoFixture, AppAssemblyFixture {

    abstract Path getTempDir()

    Path projectDir
    Path worktreesRoot
    Tracker tracker
    TrackerAdapterFactory trackerFactory

    /** Writes the shared project fixture: a single {@code build} stage that fails its first attempt. */
    void writeTwoInstanceProjectFixture() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // Written at both paths: the pipeline loader resolves `instructions:` relative to the
        // .gnomish/ root, while the runtime engine resolves the same string relative to the
        // workspace root (the task worktree) — see TakeCommandCredentialScrubSpec's own note.
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        // Attempt limit 1 + a files_exist check on a file the fake-agent scenario never creates:
        // the single attempt fails quality deterministically on BOTH instances' rounds, forcing an
        // AttemptsExhausted escalation on instance A's first round; instance B's retry only passes
        // because the fix lands directly in the shared worktree, never because the "agent" itself
        // did anything different the second time.
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
verify:
  - type: builtin
    name: files_exist
    params:
      files:
        - missing-file.txt
advancement: auto
autonomy:
  attemptLimit: 1
''')
        // tracker.type is 'github' purely to satisfy TrackerSeamValidator's registered-type check
        // (this fixture passes TrackerValidatorStub.acceptingGithub() as TakeCommand's validator
        // registry, so 'github' is a known-but-permissive type — content isn't under test here) —
        // each TakeCommand's own trackerAdapterRegistry below is independent of that seam and is
        // what actually resolves the live Tracker, so this fixture registers the real tracker under
        // the SAME key, 'github', overriding which adapter backs that type for this run.
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        commitAll(projectDir)
        // One shared worktrees root (matching the one machine-local ~/.gnomish/worktrees every
        // factory instance on a box shares, per the git-task-persistence spec) — git itself, not
        // this fixture, is what actually prevents a task branch from being checked out twice; NFR-R3
        // is about instance B needing no IN-PROCESS state from instance A, not a distinct directory.
        worktreesRoot = tempDir.resolve('worktrees')
    }

    /**
     * Builds a brand-new {@link TakeCommand}, simulating a fresh factory instance: no field or
     * object here is ever shared with any other command built by this method (NFR-R3) — a fresh
     * {@link ManualRunAssembly}, a fresh {@link FactoryProperties} (own {@code instanceName}), and
     * a fresh {@link Clock}; only {@link #worktreesRoot} and {@link #tracker}/{@link
     * #trackerFactory} (which stand in for the one shared machine-local worktrees convention and
     * the external tracker service respectively) cross into it. {@code agentCliBinary} points at
     * the fake-agent {@code plain-round} scenario wrapper (task 6.1's own technique) so both
     * instances' rounds run a real, deterministic subprocess rather than the actual {@code claude}
     * CLI.
     */
    TakeCommand newCommand(String instanceName) {
        String fakeAgentBinary = FakeAgentSupport.propertiesFor('plain-round').agentCliBinary()
        def factoryProperties = testProperties(instanceName: instanceName, agentCliBinary: fakeAgentBinary)
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: trackerFactory],
                TrackerValidatorStub.acceptingGithub())
    }

    static DefaultApplicationArguments takeArgs(String... raw) {
        new DefaultApplicationArguments(raw)
    }
}
