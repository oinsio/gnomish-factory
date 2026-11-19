package com.github.oinsio.gnomish.e2e

import static com.github.oinsio.gnomish.app.HostVerificationKill.registerRef
import static com.github.oinsio.gnomish.app.HostVerificationKill.stateAt

import com.github.oinsio.gnomish.app.ContainerE2eDocker
import com.github.oinsio.gnomish.app.FakeAgentSandboxImage
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Timeout

/**
 * M3 of migrate-verify-to-executor-contract (task 7.1; FR3, FR11, NFR-R1; design D7, D11 as amended
 * 2026-11-14, D14, D21, D22): the SonarQube-shaped reference verdict program
 * ({@link FakeAgentSandboxImage#ensureBuiltWithSonarGate}) judges a task's quality gate against a
 * fake SonarQube server ({@link SonarQubeFakeServer}), the packaged factory in container mode is
 * killed between the program's {@code start} and the key's commit, and a second packaged factory
 * resumes the task on another clone.
 *
 * <p>The kill window is D21's W1 at origin, on the medium a pickup reads: {@code start} answered
 * {@code pending}, and the pending commit carrying its key has not reached origin. The origin's
 * pre-receive hook ({@code hook-hold-pending-push}) holds the push of that commit — the order is made
 * by the hook, never hoped for — and the spec kills the factory and every process it started, the
 * held push included, the moment the hook reports it. The snapshot the attempt delivery pushed is
 * origin's register, so the second instance re-verifies the round on it and calls {@code start} again
 * (verification/pending-poll, "Kill between start and persist re-drives start"): the server sees a
 * second submission, a visible duplicate, never a wrong verdict.
 *
 * <p>Everything is read from the durable media the processes left behind — the task branch on the
 * bare origin, and what the server was asked — never from the processes' internals. Docker- and
 * guard-image-gated.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class PendingStartKillJarE2eSpec extends Specification {

    private static final String TOKEN = "sonar-e2e-${System.nanoTime()}"

    private final E2eProcessHarness harness = new E2eProcessHarness()
    private final String task = "e2e-sonar-${System.nanoTime() % 1_000_000}"
    private final List<E2eFactoryProcess> launched = []
    private SonarQubeFakeServer server

    def cleanup() {
        launched.findAll { it.alive() }.each { it.killed() }
        ContainerE2eDocker.removeTaskObjects(task)
        server?.close()
    }

    // Scenario: verification/pending-poll / Kill between start and persist re-drives start
    // Scenario: verification/pending-poll / Second recovery is a no-op
    def "M3: killed between start and the key commit, the SonarQube gate is started again by a second packaged instance and passes"() {
        given: 'the fake server, the program installed for it, and the law granting the program its token and the server'
        server = SonarQubeFakeServer.start(TOKEN)
        String image = FakeAgentSandboxImage.ensureBuiltWithSonarGate(server.address())
        Path clone = E2eGitTree.copyOf('e2e-sonar')
        clone.resolve('.gnomish/config.yaml').toFile() << "    egress: [${server.address()}]\n"
        Path origin = clone.resolveSibling('origin.git')
        E2eGitTree.publish(clone, origin)
        Path hook = StandIn.link(Files.createDirectories(origin.resolve('hooks')).resolve('pre-receive'), 'hook-hold-pending-push')
        E2eProcessHarness.projectConfig(clone, container(image))

        when: 'the first instance runs the task, and is killed while origin holds the push of its pending commit'
        def first = launch(clone, [
            '--task=pass the SonarQube quality gate',
            '--task-id=' + task
        ])
        awaitHeld(hook, first)
        def dying = first.killed()

        then: 'origin holds what the attempt delivery pushed: the round\'s snapshot, registered, and no key'
        String snapshot = E2eGitTree.git(origin, 'rev-parse', registerRef(task))
        E2eGitTree.git(origin, 'log', '-1', '--format=%s', snapshot).startsWith('gnomish: snapshot work#0')
        E2eGitTree.git(origin, 'rev-parse', branch()) == snapshot
        Files.readString(StandIn.beside(hook, 'held')).split(/\s+/).findAll() as Set == [branch(), registerRef(task)] as Set
        !dying.stderr().contains('\tat ')

        and: 'the program submitted one analysis, of the attempt commit, from its fresh box holding the token, and polled nothing'
        server.requests() == [submit(snapshot)]

        when: 'a second instance resumes the task on another clone, with origin taking every push'
        StandIn.repoint(hook, 'hook-record')
        Path other = otherClone(origin)
        def second = launch(other, ['--resume=' + task]).await(Duration.ofMinutes(10))

        then: 'the pipeline completed'
        second.exitCode() == 0
        second.stdout().contains('Stage: pipeline complete')
        !second.stderr().contains('\tat ')

        and: '`start` ran again, at the same attempt commit, and only its new task was polled, each request from a box at that commit'
        server.requests() == [
            submit(snapshot),
            submit(snapshot),
            poll('AX2', snapshot),
            poll('AX2', snapshot),
            [method: 'GET', path: '/api/qualitygates/project_status', query: [analysisId: 'AN2'], authorized: true],
        ]

        and: 'the new key landed on origin in one pending commit on the snapshot, before the round closed passing'
        def above = E2eGitTree.git(origin, 'rev-list', '--reverse', '--first-parent', "${snapshot}..${branch()}").readLines()
        E2eGitTree.git(origin, 'log', '-1', '--format=%s', above[0]).startsWith('gnomish: pending work#0 ')
        E2eGitTree.git(origin, 'rev-parse', "${above[0]}^") == snapshot
        stateAt(origin, above[0]).pendingPolls()*.key()*.value() == ['AX2']
        E2eGitTree.git(origin, 'log', '-1', '--format=%s', above[1]).startsWith('gnomish: round work#0')
        with(stateAt(origin, above[1])) {
            attempts()*.result() == [AttemptRecord.Result.PASSED]
            attempts()[0].checkResults()*.verdict().every {
                it instanceof Verdict.Pass
            }
            attempts()[0].checkResults()*.checkRef()*.label()*.forLog() == [
                'command:test -z "${SONAR_TOKEN:-}"',
                'executor:sonarqube:quality-gate'
            ]
            attemptsUsed() == 0
            pendingPolls().isEmpty()
        }

        and: 'the token reached the server from the program\'s fresh boxes only: the round box\'s own command check passed only with it absent, and no object on origin holds it'
        !E2eGitTree.git(origin, 'log', '--all', '-p', '--format=%B').contains(TOKEN)

        when: 'a further resume runs on the finished task'
        def footprint = {
            [origin: E2eGitTree.git(origin, 'for-each-ref', '--format=%(refname) %(objectname)'), asked: server.requests().size()]
        }
        def before = footprint()
        def third = launch(other, ['--resume=' + task]).await(Duration.ofMinutes(5))

        then: 'the second recovery is a no-op: origin and the server are unchanged'
        footprint() == before
        third.exitCode() == 0
        third.stdout().contains('Stage: pipeline complete')
        !third.stderr().contains('\tat ')
    }

    private E2eFactoryProcess launch(Path clone, List<String> args) {
        def process = harness.launch('run', clone, [
            '--factory.agent-cli-binary=' + FakeAgentSandboxImage.BINARY,
            '--dir=' + clone
        ] + args, [SONAR_TOKEN: TOKEN])
        launched << process
        process
    }

    /** Waits until the hook reports the held push; the factory ending first fails with what it wrote. */
    private static void awaitHeld(Path hook, E2eFactoryProcess factory) {
        Path held = StandIn.beside(hook, 'held')
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        while (!Files.exists(held)) {
            if (!factory.alive()) {
                throw new AssertionError("the factory ended before its pending commit was pushed: ${factory.await(Duration.ZERO)}")
            }
            assert System.nanoTime() <deadline: 'no pending push reached origin within 10 minutes'
            Thread.sleep(200)
        }
    }

    /** Another instance's host: a fresh clone of origin, its project bound to the box image, with none of the dying host's Docker objects. */
    private Path otherClone(Path origin) {
        ContainerE2eDocker.removeTaskObjects(task)
        Path other = Files.createTempDirectory('gnomish-e2e-other').resolve('e2e-sonar')
        E2eGitTree.git(other.parent, 'clone', '--quiet', origin.toString(), other.toString())
        E2eProcessHarness.projectConfig(other, container(image()))
        other
    }

    private String image() {
        FakeAgentSandboxImage.ensureBuiltWithSonarGate(server.address())
    }

    private String branch() {
        "refs/heads/gnomish/${task}"
    }

    private static String container(String image) {
        "factory:\n  bindings:\n    default: container\n  sandbox:\n    image: ${image}\n"
    }

    private static Map<String, Object> submit(String revision) {
        [method: 'POST', path: '/api/ce/submit', query: [projectKey: 'gnomish', revision: revision], authorized: true]
    }

    private static Map<String, Object> poll(String task, String revision) {
        [method: 'GET', path: '/api/ce/task', query: [id: task, revision: revision], authorized: true]
    }
}
