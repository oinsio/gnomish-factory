package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.MDC
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-O1 of add-tracker-port (task 5.16): the {@code taskId} MDC key is populated with the
 * canonical task id ({@code ref.id()}) for the duration of every {@code take} lifecycle action —
 * explicit-mode refusals included, not just successful claims — and reliably cleared by the time
 * {@link TakeCommand#run} returns or throws, regardless of outcome. Mirrors {@link
 * TakeCommandSpec}'s fixture shape but asserts MDC state instead of exit codes.
 */
class TakeCommandMdcSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture, ApplicationArgumentsFixture {

    private static final TaskRef REF = new TaskRef('github:acme/widgets#42')
    private static final String INSTANCE_NAME = 'gnomish-factory'
    private static final String TASK_ID_KEY = 'taskId'

    @TempDir
    Path tempDir

    Path projectDir
    Path worktreesRoot
    Tracker tracker = Mock()

    def setup() {
        MDC.remove(TASK_ID_KEY)
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
        // FR6, D5 of add-factory-serve: TakeBareAuto reads the open-front count unconditionally now
        // (FeedPolicy snapshot + OpenFrontGate per-claim re-check); default to no open fronts so
        // specs unconcerned with the WIP limit are unaffected.
        tracker.listOpen() >> []
    }

    def cleanup() {
        MDC.remove(TASK_ID_KEY)
    }

    private void writeConfig(String trackerSection = '''
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''') {
        Files.writeString(
                projectDir.resolve('.gnomish/config.yaml'),
                "schemaVersion: \"1\"\nautonomy:\n  attemptLimit: 3\n$trackerSection")
    }

    private TakeCommand newCommand(Map<String, TrackerAdapterFactory> registry) {
        // The Working row (task 6.2) reaches the takeover path; inject the headless UNAVAILABLE seam
        // rather than the production ConsoleTakeoverConfirmation so this MDC-focused spec never binds
        // to the real System.in (which a mutated confirm() would block on). Behaviour is identical: no
        // TTY, no flag → headless refusal, exactly what this spec's Working row asserts MDC around.
        TakeCommandFactory.of(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                TaskGitFixture.real(),
                worktreesRoot,
                TASK_ID_KEY,
                testProperties(instanceName: INSTANCE_NAME),
                Clock.fixed(Instant.parse('2026-01-01T00:00:00Z'), ZoneOffset.UTC),
                registry,
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                TakeCommandSeams.DEFAULTS
                .withHeartbeatSleeper(new ThreadSleeper())
                .withTakeoverConfirmation(TakeoverConfirmation.UNAVAILABLE), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly())
    }

    // FR9, UX2, NFR-O1: every explicit-mode refusal disposition (Working/AwaitingHuman/Finished/
    // Gone) never reaches TakeFreshClaim/TakeResumeBootstrap, yet the taskId MDC key must already
    // carry the canonical ref id at the moment TakeDisposition inspects trackerTask.state() —
    // captured here via the fetchTask stub closure, which runs synchronously mid-dispatch.
    def "explicit mode refusal '#state.class.simpleName' has taskId MDC set during dispatch, cleared after"() {
        given:
        writeConfig()
        String mdcDuringFetch = null
        tracker.fetchTask(_) >> {
            mdcDuringFetch = MDC.get(TASK_ID_KEY)
            new TrackerTask(REF, new TaskSnapshot('PROJ-1', 'title', 'body'), state, AbortFacts.none(), false)
        }
        // The Working row enters the takeover path (task 6.2), which reads facts via listOpen before
        // the headless (no-TTY) refusal; an empty listing renders the age as "unknown". Harmless for
        // the other states, which never reach the Working case.
        tracker.listOpen() >> []
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        expect:
        MDC.get(TASK_ID_KEY) == null

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        mdcDuringFetch == REF.id()
        MDC.get(TASK_ID_KEY) == null

        where:
        state << [
            new TrackerTaskState.Working('gnomish-other-x1y2z3'),
            new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION),
            new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT),
            new TrackerTaskState.AwaitingHuman(ParkReason.INFRA),
            new TrackerTaskState.Finished(),
            new TrackerTaskState.Gone()
        ]
    }

    // NFR-O1: FR17's no-tracker-section refusal happens before any TaskRef is even resolved —
    // nothing to set, and the finally block still leaves MDC clean.
    def "no tracker section refusal leaves taskId MDC unset throughout"() {
        given:
        writeConfig('')
        def command = newCommand([:])

        expect:
        MDC.get(TASK_ID_KEY) == null

        when:
        command.run(args('take', '42', "--dir=$projectDir"))

        then:
        thrown(UsageException)
        MDC.get(TASK_ID_KEY) == null
    }

    // NFR-O1: an uncaught exception from a live tracker call still leaves the finally block to
    // clear the key — proving cleanup happens on the exceptional path too, not only on normal
    // TakeExitCodeException termination.
    def "taskId MDC is cleared even when the tracker call throws"() {
        given:
        writeConfig()
        tracker.fetchTask(_) >> {
            throw new RuntimeException('tracker unreachable')
        }
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir"))

        then:
        thrown(RuntimeException)
        MDC.get(TASK_ID_KEY) == null
    }

    // FR10, NFR-O1: bare mode with an empty eligible queue never claims anything — no unique task
    // id to attribute the result to, so the key is never set, and stays cleared after the run.
    def "bare mode empty queue never sets taskId MDC"() {
        given:
        writeConfig()
        tracker.listReady(_) >> List.<ReadyTask> of()
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        MDC.get(TASK_ID_KEY) == null
    }

    // FR10, NFR-O1: every eligible candidate loses its claim race — TakeBareAuto's loop only sets
    // the key on an ACTUAL claim success, never per considered-and-lost candidate, so it must
    // still be unset here too.
    def "bare mode all-raced-away queue never sets taskId MDC"() {
        given:
        writeConfig()
        tracker.listReady(_) >> [
            new ReadyTask(REF, AbortFacts.none(), false, false, 'fixture title')
        ]
        tracker.claim(REF, _) >> new ClaimResult.Held('someone-else')
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        MDC.get(TASK_ID_KEY) == null
    }

    // FR9, FR10, NFR-O1: bare mode's successful claim sets taskId to the claimed candidate's ref
    // id for the duration of the dispatch (captured via fetchTask's stub closure, called right
    // after TakeBareAuto.run sets the key), then the key is cleared once TakeCommand.run returns.
    def "bare mode successful claim sets taskId MDC to the claimed ref during dispatch, cleared after"() {
        given:
        writeConfig()
        String mdcDuringFetch = 'UNSET'
        tracker.listReady(_) >> [
            new ReadyTask(REF, AbortFacts.none(), false, false, 'fixture title')
        ]
        tracker.claim(_, _) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        tracker.fetchTask(_) >> {
            mdcDuringFetch = MDC.get(TASK_ID_KEY)
            new TrackerTask(
                    REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
                    new TrackerTaskState.Finished(), AbortFacts.none(), false)
        }
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', "--dir=$projectDir"))

        then:
        thrown(TakeExitCodeException)
        mdcDuringFetch == REF.id()
        MDC.get(TASK_ID_KEY) == null
    }

    // FR9, FR11, NFR-O1 regression: a fresh claim (no existing branch) still ends up with the
    // correct taskId MDC set during the run — TakeCommand sets it before dispatch, TakeFreshClaim
    // relies on that rather than setting its own (see TakeFreshClaim's javadoc for the reasoning).
    def "explicit mode delivering a fresh Ready task has taskId MDC set for the duration, cleared after"() {
        given:
        writeConfig()
        String claimedBy = null
        List<String> mdcDuringFetch = []
        tracker.claim(_, _) >> { ref, instanceId ->
            claimedBy = instanceId; new ClaimResult.Acquired(new ClaimEpoch(1))
        }
        tracker.fetchTask(_) >> {
            mdcDuringFetch << MDC.get(TASK_ID_KEY)
            new TrackerTask(
                    REF, new TaskSnapshot('PROJ-1', 'title', 'body'),
                    claimedBy == null ? new TrackerTaskState.Ready() : new TrackerTaskState.Working((String) claimedBy),
                    AbortFacts.none(), false)
        }
        Map<String, TrackerAdapterFactory> registry = [github: fakeFactory(tracker)]
        def command = newCommand(registry)

        when:
        command.run(args('take', 'github:acme/widgets#42', "--dir=$projectDir", '--interactive'))

        then:
        thrown(TakeExitCodeException)
        mdcDuringFetch.every { it == REF.id() }
        MDC.get(TASK_ID_KEY) == null
    }
}
