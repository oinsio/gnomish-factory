package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Shared project/pipeline fixture and {@link TakeCommand} factory for {@link
 * TakeLifecycleAbortSpecBase} (task 6.4, FR10, FR14): a single-{@code agent-cli}-stage pipeline
 * with no {@code verify} checks (trivially passing on its one attempt) and an {@code
 * abort-threshold} of {@code #ABORT_THRESHOLD}, plus {@link #newCommand}, which builds a brand-new
 * {@link TakeCommand}/{@link ManualRunAssembly}/{@link FactoryProperties} trio positioned at a
 * caller-chosen {@code Instant} (design D10's backoff clock) — only the shared {@link Tracker},
 * {@link TrackerAdapterFactory}, and {@code worktreesRoot} fields cross between calls, exactly as
 * two real factory processes on one machine would share the tracker service and the machine-local
 * {@code ~/.gnomish/worktrees} convention.
 *
 * <p>Split out of the spec base purely to respect the file-size guidance
 * (`.claude/rules/process-invariants.md`) — a plain trait, not a reusable port abstraction.
 *
 * <p>Implements FR9, FR10, FR14, D10 of add-tracker-port.
 */
trait AbortLifecycleFixture implements BareGitRepoFixture, AppAssemblyFixture {

    static final int ABORT_THRESHOLD = 2
    static final Duration BACKOFF_BASE = Duration.ofMinutes(2)
    static final Duration BACKOFF_CAP = Duration.ofHours(1)

    abstract Path getTempDir()

    Path projectDir
    Path worktreesRoot
    Tracker tracker
    TrackerAdapterFactory trackerFactory

    /** Writes a single-AUTO-stage pipeline (trivially passing) with a fuse threshold of {@code #ABORT_THRESHOLD}. */
    void writeAbortProjectFixture() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // Written at both paths: the pipeline loader resolves `instructions:` relative to the
        // .gnomish/ root, while the runtime engine resolves the same string relative to the
        // workspace root (the task worktree) — see TakeCommandCredentialScrubSpec's own note.
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        // tracker.type is 'github' purely to satisfy TrackerSeamValidator's registered-type check
        // (this fixture passes TrackerValidatorStub.acceptingGithub() as the validator registry, so
        // 'github' is a known-but-permissive type — content isn't under test here) — this
        // fixture's own trackerAdapterRegistry below is independent of that seam and is what
        // actually resolves the live Tracker, so this fixture registers the real tracker under the
        // SAME key, 'github', overriding which adapter backs that type for this run.
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                """\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  abort-threshold: ${ABORT_THRESHOLD}
  github:
    api-url: https://api.github.com
    repo: acme/widgets
""")
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private FactoryProperties factoryProperties() {
        testProperties(
                agentCliBinary: FakeAgentSupport.propertiesFor('plain-round').agentCliBinary(),
                tracker: new FactoryProperties.Tracker(BACKOFF_BASE, BACKOFF_CAP))
    }

    /** Builds a fresh {@link TakeCommand}, positioned at {@code now} (design D10's backoff clock). */
    TakeCommand newCommand(Instant now) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties()),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                factoryProperties(),
                Clock.fixed(now, ZoneOffset.UTC),
                [github: trackerFactory],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }
}
