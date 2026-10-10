package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
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
 * wired {@link com.github.oinsio.gnomish.adapter.agent.CliStageExecutor}, which runs the round
 * through its {@code HostTaskExecutionEnvironment} — the task environment port that actually spawns
 * the fake agent-cli subprocess and scrubs the credential from its environment.
 *
 * <p>Implements NFR-S1, D17 of add-tracker-port.
 */
class TakeCommandCredentialScrubSpec extends Specification implements BareGitRepoFixture, TakeCommandFixture, ApplicationArgumentsFixture {

    private static final TaskRef REF = new TaskRef('github:acme/widgets#42')
    private static final String INSTANCE_NAME = 'gnomish-factory'
    private static final String CREDENTIAL_VAR = 'HOME'

    @TempDir
    Path tempDir

    Path projectDir
    RegisteredClone registeredClone
    Tracker tracker = Mock()

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // One copy only, under the law root: since D12 of add-base-ref-resolution the runtime
        // resolves `instructions:` against the same `.gnomish/` root the loader validates it
        // against, so a project-root copy would be law in neither medium.
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
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
        // FR5, FR13 of add-base-ref-resolution: a real take startup/fresh-claim resolves and
        // refreshes its base against a real 'origin' remote, never the clone's local HEAD.
        addOrigin(projectDir, tempDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), projectDir)
    }

    /**
     * The committed {@code agent-reporting-home} stand-in for the {@code claude} CLI
     * binary, through a per-run link: it records whether {@code CREDENTIAL_VAR} (this test JVM's
     * own {@code HOME}, already present in this JVM's environment and therefore in whatever
     * {@link ProcessBuilder} inherits by default) is still visible to the spawned process — i.e.
     * whether the task environment's scrub removed it before the spawned child ever ran — then
     * plays the fake-agent plain-round scenario. It deliberately does NOT export the var itself:
     * that would only prove the stand-in's own shell can set a variable, not that the launcher's
     * scrub actually ran on the inherited environment.
     */
    private Path agentStandIn
    private FactoryProperties fakeAgentProperties() {
        assert CREDENTIAL_VAR == 'HOME': 'the reporting preset records HOME'
        agentStandIn = StandIn.link(tempDir.resolve('plain-round'), 'agent-reporting-home')
        testProperties(instanceName: INSTANCE_NAME, agentCliBinary: agentStandIn.toString())
    }

    /** What the agent saw of {@code CREDENTIAL_VAR}, one entry per spawned round: present or absent. */
    private List<String> credentialReports() {
        StandInLog.blocks(agentStandIn).collect {
            it[CREDENTIAL_VAR] == 'unset' ? 'absent' : 'present'
        }
    }

    /** Declares CREDENTIAL_VAR via TrackerAdapterFactory#credentialEnvVars (design D17). */
    private TrackerAdapterFactory fakeFactoryDeclaringCredential() {
        new TrackerAdapterFactory() {
                    String type() {
                        'github'
                    }

                    Tracker create(TrackerAdapterContext context) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }

                    List<String> credentialEnvVars(TrackerConfig config) {
                        [CREDENTIAL_VAR]
                    }
                }
    }

    /** Inherits the interface's empty default credentialEnvVars() — nothing declared. */
    private TrackerAdapterFactory fakeFactoryDeclaringNoCredential() {
        new TrackerAdapterFactory() {
                    String type() {
                        'github'
                    }

                    Tracker create(TrackerAdapterContext context) {
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    // NFR-S1, D17: a fresh claim actually spawns the agent-cli stage executor's subprocess
    // (no console fallback) — the strongest available proof that the
    // declared credential never reaches the gnome, since this drives the real launcher.
    def "a fresh take claim never lets the declared tracker credential reach the spawned agent process"() {
        given: 'a Ready task, claimable, with no branch yet — a genuine fresh TakeFreshClaim run'
        String claimedBy = null
        tracker.claim(_, _) >> { TaskRef ref, String instanceId ->
            claimedBy = instanceId; new ClaimResult.Acquired(new ClaimEpoch(1))
        }
        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
            claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working(claimedBy),
            AbortFacts.none(), false)
        }
        def factoryProperties = fakeAgentProperties()
        def command = newTakeCommand(factoryProperties, registeredClone, [github: fakeFactoryDeclaringCredential()])

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then: 'the run reached a terminal exit code (proves the engine actually ran the stage)'
        thrown(TakeExitCodeException)

        and: 'the wrapper actually ran and reported (a real positive control: the report exists)'
        !credentialReports().isEmpty()

        and: 'but the spawned CLI process never saw it — the launcher scrubbed it before start'
        credentialReports() == ['absent']
    }

    // Positive control for the test above: with nothing declared to scrub (the default
    // credentialEnvVars()), the same always-present variable DOES reach the spawned process —
    // proving the "absent" result above is the scrub actually acting, not an artifact of the
    // wrapper/report mechanism or of HOME being unset in this environment for some other reason.
    def "with no credential declared, the same variable reaches the spawned agent process"() {
        given:
        String claimedBy = null
        tracker.claim(_, _) >> { TaskRef ref, String instanceId ->
            claimedBy = instanceId; new ClaimResult.Acquired(new ClaimEpoch(1))
        }
        tracker.fetchTask(_) >> {
            new TrackerTask(
            REF, new TaskSnapshot('PROJ-1', UntrustedText.tracker('title'), UntrustedText.tracker('body')),
            claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working(claimedBy),
            AbortFacts.none(), false)
        }
        def factoryProperties = fakeAgentProperties()
        def command = newTakeCommand(factoryProperties, registeredClone, [github: fakeFactoryDeclaringNoCredential()])

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        credentialReports() == ['present']
    }
}
