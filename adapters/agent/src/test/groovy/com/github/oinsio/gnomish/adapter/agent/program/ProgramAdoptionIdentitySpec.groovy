package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * The identity rule of an adopted key on a real git working copy (FR3, FR11 of
 * migrate-verify-to-executor-contract; design D14 as amended 2026-11-13, D21 "The lag the write leaves
 * behind"): the resuming run's round cell is restored from the registered chain the resume's walk
 * passed, as {@code SnapshotTipCheck} hands it on, and the view taken at the adoption is adoptable only
 * when its {@code HEAD} is the commit the factory put the working copy on — the binding's expected
 * {@code HEAD}, else the round's last registered commit — and its only other entry is the {@code
 * state.json} lag a pending commit leaves. The factory's pending commits are landed the way both media
 * land them: the branch moves, the index and the working tree stay.
 */
class ProgramAdoptionIdentitySpec extends ProgramPollSpecBase {

    static final String STATE = '.gnomish-task/state.json'
    static final RoundToken TOKEN = RoundToken.of('0a1b2c')
    static final PendingKey RECORDED = new PendingKey('reference-deferred')

    Path round
    String snapshot
    CurrentRound cell = new CurrentRound()
    List<HostTaskExecutionEnvironment> opened = []

    def cleanup() {
        opened*.dispose()
    }

    /** A task worktree whose round was snapshotted with its {@code state.json}. */
    private void snapshotRound() {
        round = initWorkingRepo(tempDir, 'round')
        Files.writeString(round.resolve('tracked.txt'), 'tracked\n')
        Files.createDirectories(round.resolve('.gnomish-task'))
        Files.writeString(round.resolve(STATE), '{"round":1}')
        commitAll(round, 'gnomish: snapshot implement#1 0a1b2c')
        snapshot = gitOutput(round, 'rev-parse', 'HEAD')
    }

    /** The factory lands a pending commit on {@code parent}, only {@code state.json} replaced; the branch moves. */
    private String landPending(String parent) {
        def side = tempDir.resolve("side-${parent.take(8)}")
        gitOutput(tempDir, 'clone', '-q', '--no-hardlinks', round.toString(), side.toString())
        gitOutput(side, 'checkout', '-q', '--detach', parent)
        Files.writeString(side.resolve(STATE), "{\"pending\":\"${parent.take(8)}\"}")
        commitAll(side, 'gnomish: pending implement#1 0a1b2c')
        def pending = gitOutput(side, 'rev-parse', 'HEAD')
        gitOutput(round, 'fetch', '-q', side.toString(), pending)
        gitOutput(round, 'update-ref', gitOutput(round, 'symbolic-ref', 'HEAD'), pending, parent)
        pending
    }

    /** The resuming run: its cell restored from the chain, its table new, its binding over {@code where}. */
    private ProgramExecutor resuming(List<String> chain, Path where = round, String expectedHead = null) {
        cell.restore(new PendingVerification(snapshot, 'implement', 1, TOKEN, Optional.empty(), chain.first(), chain))
        def environment = environmentAt(where)
        opened << environment
        executor([Capability.POLL], environment, new PendingScope(new PendingRuns(), null, expectedHead), cell)
    }

    private def adoptAndPoll(ProgramExecutor executor) {
        executor.adopt(deferred(Role.VERDICT), RECORDED)
        executor.poll(deferred(Role.VERDICT), RECORDED)
    }

    // Scenario: executor/wire-contract / A resume over the pending commit's lag adopts the key
    def "FR3: a resume on the clone where a pending commit was rotated, its index and working tree on the snapshot's state.json, adopts the key"() {
        given: 'a pending commit, then a rotated one above it; HEAD on the last registered commit'
        snapshotRound()
        def first = landPending(snapshot)
        def rotated = landPending(first)
        def executor = resuming([rotated, first, snapshot])
        int before = launches.get()

        when:
        def polled = adoptAndPoll(executor)

        then: 'the program answered the poll, judged against the view taken at the adoption'
        polled.status() == Status.COMPLETED
        polled.answer()?.path('kind')?.asText() == 'verdict'

        and: 'the view, the state.json read down the chain to the snapshot, the poll and the judging view'
        launches.get() == before + 6
    }

    // Scenario: executor/wire-contract / Adoption away from the expected commit is refused
    def "FR3: a working copy whose HEAD a killed run moved above the last registered commit is refused naming HEAD"() {
        given:
        snapshotRound()
        def pending = landPending(snapshot)
        Files.writeString(round.resolve('report.txt'), 'the judge committed\n')
        commitAll(round, 'judge was here')
        def head = gitOutput(round, 'rev-parse', 'HEAD')
        def executor = resuming([pending, snapshot])
        int before = launches.get()

        when:
        def polled = adoptAndPoll(executor)

        then:
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains("the pending key 'reference-deferred' recorded for this check cannot be adopted: "
                + "HEAD is ${head}, not the commit the factory put the working copy on (${pending})")
        launches.get() == before + 1
    }

    // Scenario: executor/wire-contract / HEAD on an earlier commit of the round's chain is refused
    def "FR3: HEAD moved back to the #which of the round's chain is refused naming HEAD, no expected HEAD set"() {
        given: 'a killed first run reset the branch onto an earlier commit of the chain'
        snapshotRound()
        def first = landPending(snapshot)
        def rotated = landPending(first)
        def earlier = which == 'snapshot' ? snapshot : first
        gitOutput(round, 'update-ref', gitOutput(round, 'symbolic-ref', 'HEAD'), earlier, rotated)
        def executor = resuming([rotated, first, snapshot])
        int before = launches.get()

        when:
        def polled = adoptAndPoll(executor)

        then:
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains("HEAD is ${earlier}, not the commit the factory put the working copy on (${rotated})")
        launches.get() == before + 1

        where:
        which << [
            'snapshot',
            'earlier pending commit'
        ]
    }

    // FR3, D14: a fresh box stands on the attempt commit its binding expects, below the round's pending commit
    def "FR3: a fresh box materialised at the attempt commit, below a registered pending commit, adopts the key"() {
        given:
        snapshotRound()
        def pending = landPending(snapshot)
        def box = tempDir.resolve('box')
        gitOutput(tempDir, 'clone', '-q', '--no-hardlinks', round.toString(), box.toString())
        gitOutput(box, 'checkout', '-q', '--detach', snapshot)
        def executor = resuming([pending, snapshot], box, snapshot)
        int before = launches.get()

        when:
        def polled = adoptAndPoll(executor)

        then: 'a clean view: no state.json read, the poll and the judging view'
        polled.status() == Status.COMPLETED
        launches.get() == before + 3
    }

    // FR3, D14: a state.json the killed run edited is no lag
    def "FR11: a state.json the killed run edited over the lag is refused naming it"() {
        given:
        snapshotRound()
        def pending = landPending(snapshot)
        Files.writeString(round.resolve(STATE), '{"forged":true}')
        def executor = resuming([pending, snapshot])

        when:
        def polled = adoptAndPoll(executor)

        then:
        polled.failureClass() == FailureClass.INFRASTRUCTURE
        polled.reason().contains("the working copy holds changes against HEAD other than the factory's state.json lag: "
                + STATE)
    }
}
