package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTrackerHarness
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.logtext.MdcAwareThread
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The healthy-cycle log proof (M1, UX1, UX2 of harden-logging-observability): one real {@code gnomish
 * serve --drain} pass over a real local git project, a real {@link InMemoryTracker} holding one
 * Ready task, and the fake agent binary standing in for the gnome — the whole claim → work →
 * deliver round, with nothing failing anywhere.
 *
 * <p>Two claims are asserted about the operator plane such a round leaves behind. <b>M1</b>: it is
 * quiet — after the startup anchor there is not one WARN or ERROR line, because nothing went
 * wrong; today's baseline for the same round was dozens of lines of sweep and poll chatter, and a
 * level demotion that regressed would surface here rather than in an operator's inbox. <b>UX2</b>:
 * it is greppable — the lines naming the task, in the order they were written, are the claim
 * anchor first, the engine's own round events in the middle, and the canonical task summary last,
 * so one grep reconstructs the task's whole story with no second search.
 *
 * <p>The capture is attached to the ROOT logger rather than to any one emitter's: "no WARN
 * anywhere" is a statement about every module the round touches, which is exactly the assertion a
 * per-class capture cannot make.
 *
 * <p>Implements FR2, FR3, FR12, M1, UX2 of harden-logging-observability.
 */
@Timeout(120)
class HealthyServeCycleLogSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture, ServeObservabilityFixture {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Path homeDir
    InMemoryTracker tracker = new InMemoryTracker()

    def setup() {
        projectDir = initWorkingRepo(tempDir, 'project')
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.createDirectories(projectDir.resolve('stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        // Written at both paths for the same reason TakeLifecycleReadyToDeliveredSpecBase does:
        // the loader resolves `instructions:` against the .gnomish/ root, the engine against the
        // workspace root.
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
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
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
        homeDir = tempDir.resolve('home')
        new InMemoryTrackerHarness(tracker).seed(
                REF, new TaskSnapshot(REF.id(), 'Add widgets', 'please add widgets'),
                new TrackerTaskState.Ready(), AbortFacts.none())
    }

    private ServeCommand newCommand() {
        def properties = FakeAgentSupport.propertiesFor('plain-round')
        new ServeCommand(
                newAssembly(properties),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                'taskId',
                properties,
                new ServeProperties(1, null, null, null, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                [github: fakeFactory(tracker)],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                new RefusingStarter(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(),
                new ClaimEpochBook())
    }

    def "M1: a healthy drain cycle that claims, works and delivers one task logs no WARN or ERROR"() {
        given: 'every line the whole factory writes during the round'
        def capture = LogCaptureSupport.attach('ROOT', Level.INFO)

        when: 'a real serve drain pass runs the seeded task end to end'
        newCommand().run(args('serve', "--dir=$projectDir", '--drain'))

        then: 'the round really did deliver — a quiet log from a round that never ran proves nothing'
        tracker.fetchTask(REF).state() instanceof TrackerTaskState.Finished

        and: 'and it stayed quiet: nothing asked the operator to act'
        def loud = capture.list.findAll {
            it.level.isGreaterOrEqual(Level.WARN)
        }
        loud.collect { "${it.level} ${it.formattedMessage}" } == []

        cleanup:
        capture.detach()
    }

    def "UX2: the task's own lines tell the story in order — claim anchor, engine events, summary last"() {
        given:
        def capture = LogCaptureSupport.attach('ROOT', Level.INFO)

        when:
        newCommand().run(args('serve', "--dir=$projectDir", '--drain'))

        then: 'the task-correlated lines, in write order'
        def story = capture.list.findAll {
            namesTask(it)
        }.collect {
            it.formattedMessage
        }

        and: 'the claim anchor opens it, before any event of the round (FR2)'
        story.first().startsWith("claim acquired for task ${REF.id()}")

        and: 'the engine\'s own round events sit in the middle, in the order the round ran them'
        def middle = story.subList(1, story.size() - 1)
        middle.findIndexOf { it.startsWith('run started:') } >= 0
        middle.findIndexOf {
            it.startsWith('run started:')
        } <middle.findIndexOf {
            it.startsWith('round started:')
        }
        middle.findIndexOf {
            it.startsWith('round started:')
        } <middle.findIndexOf {
            it.startsWith('round finished:')
        }
        middle.findIndexOf {
            it.startsWith('round finished:')
        } <middle.findIndexOf {
            it.startsWith('task finished:')
        }

        and: 'the canonical summary closes it, carrying the terminal facts (FR3, UX2)'
        story.last().startsWith('task summary: outcome=delivered')
        story.last().contains('attempts=')
        story.last().contains('wall=')
        story.last().contains('tokens=')

        cleanup:
        capture.detach()
    }

    /**
     * The operator's own filter, exactly: a line belongs to the task's story when it carries that
     * task's {@code taskId} MDC — which is what {@code grep taskId=<id>} matches once the pattern
     * has rendered it. Deliberately not a substring search for the bare id: the drain's own
     * run-level report names every task it worked, and counting it as one of the task's lines
     * would let a story that ends somewhere else still pass.
     */
    private static boolean namesTask(ILoggingEvent event) {
        event.MDCPropertyMap.get(MdcAwareThread.TASK_ID_KEY) == REF.id()
    }
}
