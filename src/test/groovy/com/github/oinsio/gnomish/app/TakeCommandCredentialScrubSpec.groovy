package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-S1, design D17 of add-tracker-port (task 5.17): the strongest practical, end-to-end proof
 * that a {@code gnomish take} run never lets the active tracker adapter's declared credential
 * reach the gnome's CLI subprocess — a fake {@link TrackerAdapterFactory} declares {@code HOME}
 * (this test JVM's own, always-present environment variable — standing in for a real credential
 * name like {@code GNOMISH_GITHUB_TOKEN}; there is no reliable, portable way to inject a brand
 * new variable into this already-running test JVM's own environment, so the strongest available
 * proof scrubs a name genuinely present in the JVM's inherited environment) via {@link
 * TrackerAdapterFactory#credentialEnvVars}, and the real {@code take} explicit-mode flow is
 * driven all the way through {@link TakeCommand} -> {@link TakeDisposition} -> {@link
 * TakeFreshClaim} -> {@link TakeEngineExecution} -> {@link ManualRunAssembly#assemble} -> the
 * wired {@link com.github.oinsio.gnomish.adapter.agent.CliStageExecutor}'s own {@link
 * com.github.oinsio.gnomish.adapter.agent.AgentProcessLauncher}, which actually spawns the fake
 * agent-cli subprocess.
 *
 * <p>Implements NFR-S1, D17 of add-tracker-port.
 */
class TakeCommandCredentialScrubSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final TaskRef REF = new TaskRef('github:acme/widgets#42')
    private static final String INSTANCE_NAME = 'gnomish-factory'
    private static final String CREDENTIAL_VAR = 'HOME'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker = Mock()

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // Written at both paths: the pipeline loader's own referenced-file existence check
        // (ReferencedFiles, FR6 of load-pipeline-config) resolves `instructions:` relative to
        // the .gnomish/ root, while the runtime engine (ControlFilePreflight) resolves the same
        // string relative to the workspace root — the task worktree, i.e. the project root —
        // so a stage instructions file that must satisfy both needs to exist at both relative
        // locations. Not a D17/NFR-S1 concern; a pre-existing quirk this end-to-end spec is the
        // first to exercise for real (every prior take spec short-circuits before CliStageExecutor).
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
        worktreesRoot = tempDir.resolve('worktrees')
    }

    /**
     * A wrapper script standing in for the {@code claude} CLI binary: reports whether {@code
     * CREDENTIAL_VAR} (this test JVM's own {@code HOME}, already present in this JVM's
     * environment and therefore in whatever {@link ProcessBuilder} inherits by default) is
     * still visible to the spawned process — i.e. whether {@link
     * com.github.oinsio.gnomish.adapter.agent.AgentProcessLauncher}'s scrub removed it before
     * this wrapper (the spawned child) ever ran — then execs the real fake-agent plain-round
     * scenario. Deliberately does NOT export the var itself: that would only prove the wrapper's
     * own shell can set a variable, not that the launcher's scrub actually ran on the inherited
     * environment.
     */
    private Path credentialReportPath
    private FactoryProperties fakeAgentProperties() {
        credentialReportPath = tempDir.resolve('credential-report.txt')
        def wrapper = File.createTempFile('fake-agent-wrapper-cred-scrub', '.sh')
        wrapper.text = """#!/bin/sh
export GNOMISH_FAKE_SCENARIO='plain-round'
if [ -n "\${${CREDENTIAL_VAR}:-}" ]; then
    echo 'present' >> '${credentialReportPath}'
else
    echo 'absent' >> '${credentialReportPath}'
fi
exec sh '${FakeAgentBinary.commandPrefix()[1]}' "\$@"
"""
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        testProperties(instanceName: INSTANCE_NAME, agentCliBinary: wrapper.absolutePath, agentCliEnvPassthrough: [])
    }

    /** Declares CREDENTIAL_VAR via TrackerAdapterFactory#credentialEnvVars (design D17). */
    private TrackerAdapterFactory fakeFactoryDeclaringCredential() {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }

                    List<String> credentialEnvVars() {
                        [CREDENTIAL_VAR]
                    }
                }
    }

    /** Inherits the interface's empty default credentialEnvVars() — nothing declared. */
    private TrackerAdapterFactory fakeFactoryDeclaringNoCredential() {
        new TrackerAdapterFactory() {
                    Tracker create(TrackerConfig config, String instanceId) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    private TakeCommand newCommand(FactoryProperties factoryProperties, Map<String, TrackerAdapterFactory> registry) {
        TakeCommandFactory.of(
                newAssembly(factoryProperties),
                worktreesRoot,
                'taskId',
                factoryProperties,
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                registry,
                TrackerValidatorStub.acceptingGithub())
    }

    private static DefaultApplicationArguments args(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    // NFR-S1, D17: a fresh claim actually spawns the agent-cli stage executor's subprocess
    // (InteractiveMode.NONE, no console fallback) — the strongest available proof that the
    // declared credential never reaches the gnome, since this drives the real launcher.
    def "a fresh take claim never lets the declared tracker credential reach the spawned agent process"() {
        given: 'a Ready task, claimable, with no branch yet — a genuine fresh TakeFreshClaim run'
        String claimedBy = null
        tracker.claim(_, _) >> { TaskRef ref, String instanceId -> claimedBy = instanceId; new ClaimResult.Acquired() }
        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
            claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working(claimedBy),
            AbortFacts.none())
        }
        def factoryProperties = fakeAgentProperties()
        def command = newCommand(factoryProperties, [github: fakeFactoryDeclaringCredential()])

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then: 'the run reached a terminal exit code (proves the engine actually ran the stage)'
        thrown(TakeExitCodeException)

        and: 'the wrapper actually ran and reported (a real positive control: the report exists)'
        credentialReportPath.toFile().exists()

        and: 'but the spawned CLI process never saw it — the launcher scrubbed it before start'
        credentialReportPath.toFile().text.trim() == 'absent'
    }

    // Positive control for the test above: with nothing declared to scrub (the default
    // credentialEnvVars()), the same always-present variable DOES reach the spawned process —
    // proving the "absent" result above is the scrub actually acting, not an artifact of the
    // wrapper/report mechanism or of HOME being unset in this environment for some other reason.
    def "with no credential declared, the same variable reaches the spawned agent process"() {
        given:
        String claimedBy = null
        tracker.claim(_, _) >> { TaskRef ref, String instanceId -> claimedBy = instanceId; new ClaimResult.Acquired() }
        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
            claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working(claimedBy),
            AbortFacts.none())
        }
        def factoryProperties = fakeAgentProperties()
        def command = newCommand(factoryProperties, [github: fakeFactoryDeclaringNoCredential()])

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        credentialReportPath.toFile().text.trim() == 'present'
    }
}
