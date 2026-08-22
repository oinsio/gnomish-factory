package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.LivenessVerdict
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, NFR-O4 of add-serve-sandbox-lifecycle: {@code take}'s startup sweep pass — that it runs at
 * all (every other take spec wires {@link SandboxLifecyclePass#NONE}, so none of them can see it),
 * that it is handed this invocation's own project directory and its liveness verdict, and that a
 * pass which fails never fails the take.
 */
class TakeCommandSweepSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    private static final TaskRef REF = new TaskRef('github:acme/widgets#42')

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker = Mock()
    List<OpenTask> openTasks = []

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        commitAll(projectDir)
        worktreesRoot = tempDir.resolve('worktrees')
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                '''schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''')
        tracker.listOpen() >> { openTasks }
        // Finished -> Skipped: the shortest run that still passes through the sweep call site.
        tracker.fetchTask(_) >> new TrackerTask(
                REF, new TaskSnapshot('PROJ-1', 'title', 'body'), new TrackerTaskState.Finished(), AbortFacts.none(), false)
    }

    private TakeCommand newCommand(SandboxLifecyclePass pass) {
        TakeCommandFactory.of(
                newAssembly(testProperties(instanceName: 'gnomish-factory')),
                TaskGitFixture.real(),
                worktreesRoot,
                'taskId',
                testProperties(instanceName: 'gnomish-factory'),
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                [github: fakeFactory(tracker)],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), pass, ContainerTakeSupport.hostOnly())
    }

    def "the startup sweep pass runs once, for this invocation's own directory and liveness verdict"() {
        given:
        def calls = []
        SandboxLifecyclePass pass = { Path dir, LivenessVerdict liveness ->
            calls << [dir, liveness]
            'sweep: 1 checked-alive'
        }

        when:
        newCommand(pass).run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        calls.size() == 1
        calls[0][0] == projectDir
        // The standing reaper has published no listing yet at startup, so the oracle fails closed:
        // no live-key set, and every tracked object is skipped rather than judged unowned (NFR-R1).
        calls[0][1] instanceof LivenessVerdict.NoVerdict
    }

    // NFR-R1: a Docker outage aborts the pass; the take has not even claimed a task yet and must
    // not fail because project-wide hygiene could not run.
    def "a failing sweep pass never fails the take"() {
        given:
        SandboxLifecyclePass pass = { Path dir, LivenessVerdict liveness ->
            throw new IllegalStateException('docker daemon is unreachable')
        }

        when:
        newCommand(pass).run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        def ex = thrown(TakeExitCodeException)
        ex.exitCode() == 15
    }
}
