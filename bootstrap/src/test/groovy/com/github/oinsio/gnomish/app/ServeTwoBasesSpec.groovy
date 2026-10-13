package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR13, UX5 of add-base-ref-resolution: one {@code serve --drain} daemon, a real project with a
 * real origin and a real {@link InMemoryTracker}, holds two tasks whose designators select two
 * different bases. Each slot binds its own task's law from its own base: the base whose
 * {@code .gnomish/} is valid runs its task, the base whose {@code .gnomish/} does not load parks its
 * task with a report naming the errors and the base, and the daemon goes on to finish its pass.
 */
@Timeout(120)
class ServeTwoBasesSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef ON_DEVELOP = new TaskRef('PROJ-DEV')
    private static final TaskRef ON_RELEASE = new TaskRef('PROJ-REL')

    @TempDir
    Path tempDir

    Path projectDir
    InMemoryTracker tracker = new InMemoryTracker()
    InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir)
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), Files.readString(projectDir.resolve('.gnomish/config.yaml')) + '''\
task-branch:
  base:
    allowed:
      - pattern: "develop"
      - pattern: "release/*"
''')
        commitAll(projectDir)
        addOrigin(projectDir, tempDir)
        def main = currentBranch(projectDir)
        pushBranch('develop', main) {
            Files.writeString(projectDir.resolve('develop.txt'), 'develop line\n')
        }
        pushBranch('release/1.18', main) {
            Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n  - ghost\n')
        }
        [(ON_DEVELOP): 'develop', (ON_RELEASE): 'release/1.18'].each { TaskRef ref, String base ->
            harness.seed(ref, new TaskSnapshot(ref.id(), UntrustedText.tracker(base), UntrustedText.tracker('body')),
            new TrackerTaskState.Ready(), AbortFacts.none())
            harness.seedDesignators(ref, TaskDesignators.of('base', Designator.single(base)))
        }
    }

    /** A branch cut from {@code main}, edited by {@code edit}, pushed to origin; the checkout returns to {@code main}. */
    private void pushBranch(String branch, String main, Closure edit) {
        gitOutput(projectDir, 'checkout', '-q', '-b', branch)
        edit()
        commitAll(projectDir, "base ${branch}")
        assert gitExitCode(projectDir, 'push', '-q', 'origin', "${branch}:refs/heads/${branch}") == 0
        gitOutput(projectDir, 'checkout', '-q', main)
    }

    // F99: the park report's "Base ref:" line names the ref the task resolved to, not the law commit SHA
    // Scenario: serve/factory-serve / Two bases, one broken
    // Scenario: pipeline/pipeline-config / A broken base parks only its task
    def "FR13: the task on the valid base runs, the task on the broken base parks naming its errors and base, and the daemon runs on"() {
        when: 'one drain pass works the queue'
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        newDrainCommand(properties, newAssembly(properties), RegisteredCloneFixture.unregistered(tempDir.resolve('home'), projectDir),
                fakeFactory(tracker)).run(args('serve', "--dir=$projectDir", '--drain'))

        then: 'the daemon finished its pass: the run returned normally'
        noExceptionThrown()

        and: 'the develop task ran: its task branch exists, and it is no longer waiting in the queue'
        gitExitCode(projectDir, 'rev-parse', '--verify', '-q', "refs/heads/gnomish/${ON_DEVELOP.id()}") == 0
        !(tracker.fetchTask(ON_DEVELOP).state() instanceof TrackerTaskState.Ready)

        and: 'the release task parked, with a report naming the located error and the base'
        tracker.fetchTask(ON_RELEASE).state() instanceof TrackerTaskState.AwaitingHuman
        def report = harness.threadAsStrings(ON_RELEASE).join('\n')
        report.contains('Base ref: release/1.18')
        report.contains('ghost')
    }
}
