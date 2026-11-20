package com.github.oinsio.gnomish.testfixtures.standin

import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, FR12 of migrate-verify-to-executor-contract (design D11): the stand-in executors on the
 * re-poll of an ended run. The reference answers every poll of its key with the run's answer; the
 * fake told to break {@code pending-repoll} forgets the run its first poll ended — that poll answers
 * as the reference does and lists the run's working copy as ended in the file beside the fake's link,
 * outside every working copy (D11 as amended 2026-11-14), and every later poll from that copy is
 * refused naming the key — while in another workspace it answers as the reference. The
 * {@code pending-in-copy} preset keeps its pending run in its working copy's {@code .git}, so a poll
 * from another copy is refused. Requests are written by the contract's own writer, as {@code
 * ProgramExecutor} writes them; the case's workspace is a real directory holding a {@code .git}, as
 * each kit case's working copy does.
 */
class StandInExecutorRepollSpec extends Specification {

    @TempDir
    Path tempDir

    def "FR11: the reference answers a re-poll of its ended run's key alike, every time"() {
        given:
        Files.createDirectories(tempDir.resolve('cases/pending-repoll/.git'))
        def deferred = request('pending-repoll')

        when:
        def polls = (1..3).collect {
            run(StandIn.executor('reference'), deferred)
        }

        then:
        polls*.exit == [0, 0, 0]
        polls*.result.toSet().size() == 1
        result(polls.first()).status() == Status.COMPLETED
        !Files.exists(tempDir.resolve('cases/pending-repoll/.git/gnomish-ended-run'))
    }

    def "FR11, FR12: break-pending-repoll answers its case's first poll, refuses the re-poll, and re-polls elsewhere alike"() {
        given:
        def fake = StandIn.executor('break-pending-repoll')
        Path repository = Files.createDirectories(tempDir.resolve('cases/pending-repoll/.git'))
        Files.createDirectories(tempDir.resolve('cases/elsewhere/.git'))

        when:
        def first = run(fake, request('pending-repoll'))
        def second = run(fake, request('pending-repoll'))
        def other = [
            run(fake, request('elsewhere')),
            run(fake, request('elsewhere'))
        ]

        then:
        first.exit == 0 && result(first).status() == Status.COMPLETED
        StandIn.beside(fake, 'ended-runs').toFile().readLines().contains(repository.parent.toString())
        repository.toFile().list() as List == []
        second.exit == 2 && second.result == null && second.stderr.contains("no pending run has the key 'reference-deferred'")
        other*.exit == [0, 0]
        other*.result.toSet().size() == 1 && result(other[0]).status() == Status.COMPLETED
        !StandIn.beside(fake, 'ended-runs').toFile().readLines().contains(tempDir.resolve('cases/elsewhere').toString())
    }

    def "FR11: pending-in-copy records its pending run in its working copy's .git at start, and refuses a poll from a copy without it"() {
        given:
        def keeper = StandIn.executor('pending-in-copy')
        Path started = Files.createDirectories(tempDir.resolve('cases/pending-poll/.git'))
        Files.createDirectories(tempDir.resolve('cases/poll/pending-poll'))

        when:
        def start = run(keeper, request('pending-poll'), 'start')
        def same = run(keeper, request('pending-poll'))
        def other = run(keeper, request('poll/pending-poll'))

        then:
        start.exit == 0 && result(start).status() == Status.PENDING
        Files.exists(started.resolve('gnomish-pending-run'))
        same.exit == 0 && result(same).status() == Status.COMPLETED
        other.exit == 2 && other.result == null
        other.stderr.contains("poll refused: no pending run has the key 'reference-deferred' in this working copy")
    }

    private String request(String kase) {
        def written = new ExecutorRequest(Role.TRANSFORM, new Workspace(tempDir.resolve('cases').resolve(kase).toString()),
                new Policy.WorkspaceWrite(), RoleInputs.transform('Implement the task.'),
                ExecutorContractJson.settingsTree([defer: true]), Bounds.none(), [], null)
        ExecutorContractJson.writer().writeValueAsString(written)
    }

    private static ExecutorResult result(Map run) {
        ExecutorContractJson.reader().forType(ExecutorResult).readValue(run.result as String)
    }

    /** Runs {@code operation} of {@code binary} with the key the reference issues, the request on stdin; exit, stderr, result. */
    private Map run(Path binary, String request, String operation = 'poll') {
        Path dir = Files.createTempDirectory(tempDir, 'run-')
        Path stdin = Files.writeString(dir.resolve('request.json'), request)
        Path resultFile = dir.resolve('result.json')
        def builder = new ProcessBuilder([binary.toString(), operation]).directory(dir.toFile()).redirectInput(stdin.toFile())
        TestChildEnvironment.cleared(builder).putAll([GNOMISH_RESULT_FILE: resultFile.toString(), GNOMISH_PENDING_KEY: 'reference-deferred'])
        Process process = builder.start()
        process.inputStream.text
        String stderr = process.errorStream.text
        [exit: process.waitFor(), stderr: stderr, result: Files.exists(resultFile) ? resultFile.toFile().text : null]
    }
}
