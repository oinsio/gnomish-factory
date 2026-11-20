package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.check.PendingAdoption
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path

/**
 * The check binding of a {@code program} through a fresh box per request (FR3, FR11, NFR-S1 of
 * migrate-verify-to-executor-contract; design D6, D11 as amended 2026-11-14, D14 as amended
 * 2026-11-13): each request gets a box of its own at the attempt commit the check's workspace names,
 * built for the executor's own grant and released by the request's end; the run's table carries a
 * {@code start}'s key to a poll in another box, scoped to the check; a recorded key is adopted and
 * polled in one box against that commit; and a request that has no box answers {@code
 * failed/infrastructure} with no process spawned. The boxes are real clones of the spec's repository
 * under host environments, the program the stand-in reference executor.
 */
class ProgramCheckBindingSpec extends ProgramPollSpecBase {

    static final CheckRef REF = CheckRef.of(0, VerifyChecks.command('lint'))
    static final CheckRef OTHER = CheckRef.of(1, VerifyChecks.command('docs'))
    static final PendingKey KEY = new PendingKey('reference-deferred')

    /** Every box the source built: the commit it was put on, the grant its guard admits, its root. */
    List<Map> boxes = []

    int released

    String commit

    def setup() {
        commit = gitOutput(repo, 'rev-parse', 'HEAD')
    }

    private FreshBoxSource source() {
        { String attemptCommit, List<String> egress ->
            Path root = tempDir.resolve("box-${boxes.size()}")
            gitOutput(tempDir, 'clone', '-q', '--no-hardlinks', repo.toString(), root.toString())
            gitOutput(root, 'checkout', '-q', '--detach', attemptCommit)
            def box = environmentAt(root)
            boxes << [commit: attemptCommit, egress: egress, root: root]
            new CheckEnvironmentSource.Acquired() {
                        TaskExecutionEnvironment environment() {
                            box
                        }

                        void close() {
                            released++
                            box.dispose()
                        }
                    }
        } as FreshBoxSource
    }

    private ProgramCheckBinding binding(PendingRuns table = runs, FreshBoxSource boxes = source()) {
        def declaration = new ExecutorDeclaration(ProgramFixtures.NAME, ExecutorDeclaration.Type.PROGRAM,
                [
                    StandIn.executor('reference').toString()
                ], [:], [], [' API.Acme.Example '])
        new ProgramCheckBinding(new DescribedProgram(declaration, ProgramFixtures.describe([Capability.POLL]), []),
        ProgramFixtures.equipment(stderrParent), table, boxes)
    }

    private AttemptCommitWorkspace attempt() {
        { -> commit } as AttemptCommitWorkspace
    }

    def "FR11, D11: a start and its poll run in two boxes at the attempt commit, the key carried by the run's table"() {
        given:
        def binding = binding()

        when: 'the start, then the poll, each a request of its own'
        def first = binding.bind(REF, attempt())
        def started = first.executor().start(deferred(Role.VERDICT))
        first.release().run()
        def second = binding.bind(REF, attempt())
        def polled = second.executor().poll(deferred(Role.VERDICT), KEY)
        second.release().run()

        then: 'pending, then the verdict, judged against the view the start took in the first box'
        started.pendingKey() == KEY
        polled.status() == Status.COMPLETED
        polled.answer()?.path('kind')?.asText() == 'verdict'

        and: 'two boxes, each at the attempt commit, each built for the executor grant alone and released'
        boxes*.commit == [commit, commit]
        boxes*.root.unique().size() == 2
        boxes*.root.every {
            gitOutput(it as Path, 'rev-parse', 'HEAD') == commit
        }
        boxes*.egress == [
            ['api.acme.example'],
            ['api.acme.example']
        ]
        released == 2
    }

    def "FR3, D14: a recorded key is adopted and polled in one box, expected on the commit the box was put on"() {
        given: 'a new run, its table empty'
        def bound = binding(new PendingRuns()).bind(REF, attempt())

        when:
        (bound.executor() as PendingAdoption).adopt(deferred(Role.VERDICT), KEY)
        def polled = bound.executor().poll(deferred(Role.VERDICT), KEY)
        bound.release().run()

        then:
        polled.status() == Status.COMPLETED
        boxes.size() == 1
        released == 1
    }

    def "D14: a key the run issued for one check is unknown to another check of the same program"() {
        given:
        def binding = binding()
        def first = binding.bind(REF, attempt())
        first.executor().start(deferred(Role.VERDICT))
        first.release().run()
        int before = launches.get()

        when:
        def other = binding.bind(OTHER, attempt())
        def polled = other.executor().poll(deferred(Role.VERDICT), KEY)
        other.release().run()

        then: 'refused before any process'
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains('is unknown')
        launches.get() == before
    }

    def "NFR-R1: a box that cannot be materialized answers every operation failed/infrastructure, spawning nothing"() {
        given:
        def failing = { String attemptCommit, List<String> egress ->
            throw new IllegalStateException('docker died')
        } as FreshBoxSource
        int before = launches.get()

        when:
        def bound = binding(runs, failing).bind(REF, attempt())
        def started = bound.executor().start(deferred(Role.VERDICT))
        def polled = bound.executor().poll(deferred(Role.VERDICT), KEY)
        bound.release().run()

        then:
        [started, polled].every {
            it.failureClass() == FailureClass.INFRASTRUCTURE &&
            it.reason().contains("its fresh box at ${commit} could not be materialized: docker died")
        }
        !(bound.executor() instanceof PendingAdoption)
        bound.executor().describe().capabilities() == [Capability.POLL]
        launches.get() == before
    }

    def "D11: a working copy that names no attempt commit gets no box, and its start is refused"() {
        when:
        def bound = binding().bind(REF, new DirectoryWorkspace(repo))
        def started = bound.executor().start(deferred(Role.VERDICT))

        then:
        started.failureClass() == FailureClass.INFRASTRUCTURE
        started.reason().contains('runs in a fresh box at the attempt commit, and the working copy names none (DirectoryWorkspace)')
        boxes.isEmpty()
    }
}
