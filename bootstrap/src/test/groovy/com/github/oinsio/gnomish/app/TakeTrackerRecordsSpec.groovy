package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * What crosses from the tracker into a worked task's own records, on a real {@code take} of one
 * GitHub-shaped task to delivery: the canonical id, unchanged, into {@code task.json}, the log MDC and
 * the branch name; the tracker's credential, live in the run, into no prompt.
 *
 * <p>Implements FR16, NFR-O1, NFR-S1 of add-tracker-port; FR2 of add-git-workflow.
 */
class TakeTrackerRecordsSpec extends Specification implements BareGitRepoFixture, TakeCommandFixture, ApplicationArgumentsFixture {

    private static final TaskRef REF = new TaskRef('github:ghe.example.com/acme/widgets#42')
    private static final String TOKEN = 'ghp_tracker_token_never_in_a_prompt'

    @TempDir
    Path tempDir

    Path projectDir
    Path origin
    RegisteredClone registeredClone
    Tracker tracker = Mock()
    List<String> mdcAtTrackerCalls = []

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: claude-fake-main-1
instructions: stages/build/instructions.md
advancement: auto
''')
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://ghe.example.com/api/v3
    repo: acme/widgets
''')
        commitAll(projectDir)
        origin = addOrigin(projectDir, tempDir)
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), projectDir)

        String claimedBy = null
        tracker.listOpen() >> []
        tracker.claim(_, _) >> { TaskRef ref, String instanceId ->
            mdcAtTrackerCalls << MDC.get('taskId')
            claimedBy = instanceId
            new ClaimResult.Acquired(new ClaimEpoch(1))
        }
        tracker.fetchTask(_) >> {
            new TrackerTask(REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('a task'), UntrustedText.tracker('do it')),
            claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working(claimedBy),
            AbortFacts.none(), false)
        }
        tracker.finish(_, _) >> { mdcAtTrackerCalls << MDC.get('taskId') }
    }

    /** A take command whose tracker wiring resolves {@code secrets}, exactly as {@link TakeCommandFixture} builds one. */
    private TakeCommand takeCommand(FactoryProperties properties, Map<String, String> secrets, TrackerAdapterFactory factory) {
        TakeCommands.of(
                newAssembly(properties, TakeCommands.slotTime()), TaskGitFixture.real(), registeredClone, 'taskId', properties,
                new TrackerWiring([github: factory], new MapSecretsProvider(secrets),
                TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()), { time ->
                    TakeCommandSeams.defaults(time, LiveConsoleIO.onStdout())
                }, SandboxLifecyclePass.NONE, ContainerTakeSupportFixture.hostOnly())
    }

    /** The GitHub adapter's factory shape: it declares and resolves the token, and hands out the mock. */
    private TrackerAdapterFactory factoryReadingToken(List<String> resolved) {
        new TrackerAdapterFactory() {
                    String type() {
                        'github'
                    }

                    Tracker create(TrackerAdapterContext context) {
                        resolved << context.secrets().find('GNOMISH_GITHUB_TOKEN').orElse(null)
                        tracker
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('refs are canonical here')
                    }

                    List<String> credentialEnvVars(TrackerConfig config) {
                        ['GNOMISH_GITHUB_TOKEN']
                    }
                }
    }

    private int take(TakeCommand command) {
        try {
            command.run(args('take', REF.id(), "--dir=$projectDir"))
            throw new IllegalStateException('take returned without an exit code')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    // FR16, NFR-O1 of add-tracker-port; FR2 of add-git-workflow: the id is opaque to core.
    // Scenario: tracker/github-tracker / The canonical id flows unchanged into the records
    def "a delivered task's task.json, log MDC and branch name carry its canonical id unchanged"() {
        given:
        def properties = testProperties(instanceName: 'gnomish-factory', agentCliBinary: FakeAgentSupport.wrapperFor('plain-round'))

        when:
        def exit = take(takeCommand(properties, [:], factoryReadingToken([])))

        then: 'the task was delivered'
        exit == 0

        and: 'the MDC named the task by its canonical id at every tracker write of the run'
        mdcAtTrackerCalls.size() == 2
        mdcAtTrackerCalls.every { it == REF.id() }

        and: 'the branch is the id through the git-task-persistence sanitize, and task.json holds the id itself'
        def branch = 'gnomish/' + TaskIdSanitizer.sanitize(REF.id())
        branch == 'gnomish/github-ghe.example.com-acme-widgets-42'
        // Delivery retires the envelope from the tip, so task.json is read where the claim wrote it.
        def written = gitOutput(origin, 'log', '--format=%H', '--diff-filter=A', "refs/heads/${branch}", '--',
                EnvelopePaths.TASK_JSON_PATH).trim()
        def taskJson = new ObjectMapper().readTree(gitOutput(origin, 'show', "${written}:${EnvelopePaths.TASK_JSON_PATH}"))
        taskJson.get('taskId').asText() == REF.id()
    }

    // NFR-S1 of add-tracker-port: the credential lives in the adapter, never in what the gnome reads.
    // Scenario: tracker/github-tracker / The token never reaches a prompt
    def "the tracker token, resolved for the run's adapter, appears in no prompt the run assembles"() {
        given: 'an agent that records every round prompt it is handed'
        def prompts = tempDir.resolve('plain-round.log')
        def properties = testProperties(instanceName: 'gnomish-factory',
        agentCliBinary: FakeAgentSupport.binaryCapturingStdin('plain-round', prompts))
        def resolved = []

        when:
        def exit = take(takeCommand(properties, [GNOMISH_GITHUB_TOKEN: TOKEN], factoryReadingToken(resolved)))

        then: 'the adapter did hold the token while the task was worked'
        exit == 0
        resolved.contains(TOKEN)

        and: 'a prompt was assembled and handed to the agent, and it carries no tracker credential'
        Files.exists(prompts)
        Files.readString(prompts).contains('build it')
        !Files.readString(prompts).contains(TOKEN)
    }
}
