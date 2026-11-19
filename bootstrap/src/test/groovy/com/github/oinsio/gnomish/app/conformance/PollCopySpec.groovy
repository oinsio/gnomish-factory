package com.github.oinsio.gnomish.app.conformance

import static com.github.oinsio.gnomish.app.conformance.KitSubjectDouble.*

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, FR12 of migrate-verify-to-executor-contract (design D11 as amended 2026-11-14): the working
 * copy a pending case's polls name — at {@code <scratch>/poll/<case>}, holding for {@code transform}
 * the files of {@code start}'s copy without its {@code .git}, and for {@code verdict} and {@code
 * decision} the content before {@code start} — and the in-process candidate polling from it.
 */
class PollCopySpec extends Specification {

    @TempDir
    Path scratch

    private Path startCopy() {
        Path start = Files.createDirectory(scratch.resolve('pending-poll'))
        Files.createDirectories(start.resolve('.git/refs'))
        start.resolve('.git/HEAD').toFile().text = 'ref: refs/heads/main\n'
        Files.createDirectories(start.resolve('src/.git'))
        start.resolve('src/Main.java').toFile().text = 'class Main {}\n'
        start.resolve('src/.git/nested').toFile().text = 'kept\n'
        start.resolve('written.txt').toFile().text = 'start wrote this\n'
        start
    }

    private static List<String> listing(Path root) {
        Files.walk(root).withCloseable {
            it.filter { p ->
                p != root
            }.map { p ->
                root.relativize(p).toString()
            }.sorted().toList()
        }
    }

    def "FR11: a transform poll copy holds start's files and directories, without start's own .git"() {
        given:
        def start = startCopy()
        def before = []

        when:
        def copy = PollCopy.create(scratch, KitCase.PENDING_POLL, Role.TRANSFORM, start, {
            before << it
        })

        then:
        copy == scratch.resolve('poll').resolve('pending-poll')
        listing(copy) == [
            'src',
            'src/.git',
            'src/.git/nested',
            'src/Main.java',
            'written.txt'
        ]
        copy.resolve('written.txt').toFile().text == 'start wrote this\n'
        before == []
    }

    def "FR11: a .git file at the top of start's copy is not carried either"() {
        given:
        Path start = Files.createDirectory(scratch.resolve('pending-repoll'))
        start.resolve('.git').toFile().text = 'gitdir: /elsewhere\n'
        start.resolve('kept.txt').toFile().text = 'k\n'

        when:
        def copy = PollCopy.create(scratch, KitCase.PENDING_REPOLL, Role.TRANSFORM, start, {})

        then:
        listing(copy) == ['kept.txt']
    }

    def "FR11: a #role poll copy holds the content before start, made by what made start's copy, none of start's files"() {
        given:
        def start = startCopy()
        def before = []

        when:
        def copy = PollCopy.create(scratch, KitCase.PENDING_POLL, role, start, { Path p ->
            before << p; p.resolve('made.txt').toFile().text = 'before\n'
        })

        then:
        before == [copy]
        listing(copy) == ['made.txt']

        where:
        role << [Role.VERDICT, Role.DECISION]
    }

    def "FR11: a second poll copy of one case is refused, and two cases share the poll directory"() {
        given:
        def start = startCopy()
        PollCopy.create(scratch, KitCase.PENDING_POLL, Role.VERDICT, start, {})

        when:
        PollCopy.create(scratch, KitCase.PENDING_REPOLL, Role.VERDICT, start, {})
        PollCopy.create(scratch, KitCase.PENDING_POLL, Role.VERDICT, start, {})

        then:
        def e = thrown(UncheckedIOException)
        e.message == 'could not make the poll copy of case pending-poll'
        listing(scratch.resolve('poll')) == [
            'pending-poll',
            'pending-repoll'
        ]
    }

    // Scenario: executor/conformance-kit / A verdict candidate polls from the content before start
    def "FR11, FR12: an in-process verdict candidate's poll is bound to a working copy holding none of the files start wrote"() {
        given: 'an executor per binding, which writes into its copy at start and lists its copy at poll'
        Map<Path, List<String>> polledFrom = [:]
        Map<Path, Path> boundTo = [:]
        def binding = { KitCase kase, Path copy ->
            def executor
            executor = [describe: {
                    describeOf([Role.VERDICT], [Capability.POLL])
                }, start: { ExecutorRequest r ->
                    if (r.settings().path('defer').asBoolean()) {
                        copy.resolve('verdict-notes.txt').toFile().text = 'written by start\n'
                        return result(Status.PENDING, null, 'v1')
                    }
                    result(Status.COMPLETED, VERDICT)
                }, poll: { ExecutorRequest r, PendingKey k ->
                    polledFrom[copy] = listing(copy)
                    boundTo[copy] = Path.of(r.workspace().path())
                    result(Status.COMPLETED, VERDICT)
                }] as Executor
            executor
        } as CaseBinding
        def kitScratch = Files.createDirectory(scratch.resolve('kit'))
        def candidate = new BuiltInCandidate(describeOf([Role.VERDICT], [Capability.POLL]), binding, [:], kitScratch)

        when:
        def report = new ConformanceKit([defer: true], { Duration d -> } as Sleeper).run(candidate)

        then:
        report.verdicts()[KitCase.PENDING_POLL] instanceof CaseVerdict.Passed
        report.verdicts()[KitCase.PENDING_REPOLL] instanceof CaseVerdict.Passed
        def pollCopy = kitScratch.resolve('poll').resolve('pending-poll')
        polledFrom[pollCopy] == []
        boundTo[pollCopy] == pollCopy
        kitScratch.resolve('pending-poll').resolve('verdict-notes.txt').toFile().text == 'written by start\n'
        polledFrom.keySet() == [
            pollCopy,
            kitScratch.resolve('poll').resolve('pending-repoll')
        ] as Set
    }
}
