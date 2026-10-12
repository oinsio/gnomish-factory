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
 * The two tiers of one task's configuration (FR2, FR11, FR12 of add-base-ref-resolution): which base
 * a task may run on is decided by the trusted tier, bound at startup from the default branch, while
 * everything else in the chosen base's {@code .gnomish/} — its {@code config.yaml} included — binds
 * from that base's own law commit. Driven through one {@code serve --drain} pass over a real origin
 * and the in-memory tracker, as {@link ServeTwoBasesSpec} drives two bases.
 *
 * <p>Implements FR2, FR11, FR12 of add-base-ref-resolution.
 */
@Timeout(120)
class ServeChosenBaseSectionSpec extends Specification
implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef ON_RELEASE = new TaskRef('PROJ-REL')

    @TempDir
    Path tempDir

    Path projectDir
    InMemoryTracker tracker = new InMemoryTracker()
    InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        writeMinimalProject(projectDir)
        appendConfig('task-branch:\n  base:\n    allowed:\n      - pattern: "release/*"\n')
        commitAll(projectDir)
        addOrigin(projectDir, tempDir)
        def main = currentBranch(projectDir)
        gitOutput(projectDir, 'checkout', '-q', '-b', 'release/1.18')
        // The base's own config.yaml: a task-branch section that would refuse this very base, and
        // a one-attempt limit; its stage gains a check the fake agent's round never satisfies.
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'),
                Files.readString(projectDir.resolve('.gnomish/config.yaml'))
                .replace('attemptLimit: 3', 'attemptLimit: 1')
                .replace('pattern: "release/*"', 'pattern: "hotfix/*"'))
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'),
                Files.readString(projectDir.resolve('.gnomish/stages/build/stage.yaml'))
                .replace('advancement: auto', 'verify:\n  - type: command\n    command: exit 1\nadvancement: auto'))
        commitAll(projectDir, 'base release/1.18')
        assert gitExitCode(projectDir, 'push', '-q', 'origin', 'release/1.18:refs/heads/release/1.18') == 0
        gitOutput(projectDir, 'checkout', '-q', main)
        harness.seed(ON_RELEASE, new TaskSnapshot(ON_RELEASE.id(), UntrustedText.tracker('release work'),
                UntrustedText.tracker('body')), new TrackerTaskState.Ready(), AbortFacts.none())
        harness.seedDesignators(ON_RELEASE, TaskDesignators.of('base', Designator.single('release/1.18')))
    }

    private void appendConfig(String text) {
        def config = projectDir.resolve('.gnomish/config.yaml')
        Files.writeString(config, Files.readString(config) + text)
    }

    // Scenario: pipeline/pipeline-config / The chosen base's own `task-branch.base` section plays no part in choosing it
    def "FR11, FR12: the default branch's section admits the base, and the base's own config.yaml governs the task"() {
        when: 'one drain pass works the queue'
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        newDrainCommand(properties, newAssembly(properties), RegisteredCloneFixture.unregistered(tempDir.resolve('home'), projectDir),
                fakeFactory(tracker)).run(args('serve', "--dir=$projectDir", '--drain'))

        then: 'the base was admitted by the default branch\'s section: the task ran on it'
        gitExitCode(projectDir, 'rev-parse', '--verify', '-q', "refs/heads/gnomish/${ON_RELEASE.id()}") == 0

        and: 'the attempt limit and the check came from release/1.18\'s own law: one failed attempt escalated'
        tracker.fetchTask(ON_RELEASE).state() instanceof TrackerTaskState.AwaitingHuman
        harness.threadAsStrings(ON_RELEASE).join('\n').contains('Attempt limit (1) reached')
    }
}
