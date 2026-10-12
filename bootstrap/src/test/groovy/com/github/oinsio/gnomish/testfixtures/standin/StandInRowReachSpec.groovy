package com.github.oinsio.gnomish.testfixtures.standin

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR26 of supervise-daemon-loops-and-embed-dashboard (design D26, ADR 0015): no row of the stand-in
 * library is dead. Each row of each preset is run once, by an invocation built from its own match,
 * through a per-run link, with {@code STAND_IN_REACHED} naming the file the script marks matched rows
 * in; the last feature asserts that every row was marked and names the misses. A row an earlier
 * terminal row shadows — a second answer for one argv, anything after a {@code *} terminal — is
 * exactly the row no invocation reaches. The check is the bats-mock {@code unstub} idea, applied
 * once, here, never per consumer.
 *
 * <p>A run is stopped as soon as its row is marked: what the row then does (a stall, the real git,
 * the fake agent) is {@link StandInLibrarySpec}'s subject, not this one's.
 */
class StandInRowReachSpec extends Specification {

    /** The argument a {@code *} match word, or a glob character inside one, is replaced by. */
    private static final String ANY = 'stand-in-probe'

    @Shared
    @TempDir
    Path shared

    @TempDir
    Path tempDir

    def "FR26: each row of preset #preset is run by an invocation built from its match"() {
        given:
        List<List<String>> rows = StandIn.tables().rows(preset)
        String name = linkName(rows)

        when:
        rows.eachWithIndex { List<String> row, int index ->
            Path dir = Files.createDirectories(tempDir.resolve("row-${index + 1}"))
            probe(StandIn.link(dir.resolve(name), preset), argvOf(row[0], name), "${preset} ${index + 1}")
        }

        then:
        noExceptionThrown()

        where:
        preset << StandInLibrarySpec.presets()
    }

    // Scenario: build/quality-gates / A dead row fails the library spec
    def "FR26: every row of every preset was reached — a miss is a dead row, named here"() {
        given:
        Set<String> reached = Files.exists(reachedFile()) ? reachedFile().toFile().readLines().toSet() : [] as Set

        expect:
        StandInLibrarySpec.presets().collectMany { String preset ->
            (1..StandIn.tables().rows(preset).size()).collect {
                "${preset} ${it}".toString()
            }
        }.findAll { !(it in reached) } == []
    }

    /** The argv that the row's match accepts: each word as written, a glob character made concrete. */
    private static List<String> argvOf(String match, String name) {
        if (match == '*') {
            return [ANY]
        }
        match.split(',').collect { String word ->
            word == '@name' ? name : word.replaceAll(/[*?]/, 'x')
        }
    }

    /**
     * The per-run link's name: the first answer of the file a {@code <file>#@name} row reads, so that
     * answer exists; otherwise a plain name an {@code @name} match then expects as its argument.
     */
    private static String linkName(List<List<String>> rows) {
        String ref = rows.flatten().find { String word ->
            word.endsWith('#@name')
        } as String
        if (ref == null) {
            return 'probe'
        }
        Path file = StandIn.library().resolve(ref - '#@name')
        String header = file.toFile().readLines().find { it ==~ /\[[^\]]+\]/ }
        header[1..-2]
    }

    /** Runs {@code link} until {@code mark} is in the reached file or the run ends, then stops it. */
    private void probe(Path link, List<String> argv, String mark) {
        def builder = new ProcessBuilder([link.toString()] + argv)
        .directory(link.parent.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        TestChildEnvironment.cleared(builder).put('STAND_IN_REACHED', reachedFile().toString())
        Process process = builder.start()
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() <deadline && !marked(mark) && !process.waitFor(20, TimeUnit.MILLISECONDS)) {
                // polling: the row is marked before its action runs, so a stall is cut short here
            }
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor()
        }
    }

    private boolean marked(String mark) {
        Files.exists(reachedFile()) && mark in reachedFile().toFile().readLines()
    }

    private Path reachedFile() {
        shared.resolve('reached')
    }
}
