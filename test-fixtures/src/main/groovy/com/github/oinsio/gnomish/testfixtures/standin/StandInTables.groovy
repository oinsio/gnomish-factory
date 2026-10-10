package com.github.oinsio.gnomish.testfixtures.standin

import java.nio.file.Files
import java.nio.file.Path

/**
 * The index of the stand-in library (ADR 0015): which table under {@code tables/} holds a
 * preset's section, and the rows of that section — read the way {@code stand-in.sh} reads them,
 * so {@link StandIn} and the library's own spec judge a preset by the rows the script will run.
 *
 * <p>A table holds several presets, one section each, headed {@code [<preset>]}; comment lines
 * start with {@code #}. The script's header is the grammar of the rows.
 */
final class StandInTables {

    /** The actions only a per-run link may run: they write beside it or take its name. */
    static final Set<String> PER_RUN_ACTIONS = [
        'record',
        'write',
        'export-name'
    ].asImmutable()

    private final Path library

    StandInTables(Path library) {
        this.library = library
    }

    /** Every table file of the library, in name order. */
    List<Path> tables() {
        Files.list(library.resolve('tables')).withCloseable { stream ->
            stream.filter {
                it.fileName.toString().endsWith('.params')
            }.sorted().toList()
        }
    }

    /** The preset names each table declares, by table. */
    Map<Path, List<String>> sections() {
        tables().collectEntries { Path table ->
            [(table): table.toFile().readLines().findAll {
                    it ==~ /\[[^\]]+\]/
                }.collect {
                    it[1..-2]
                }]
        }
    }

    /** The one table holding {@code preset}'s section. */
    Path tableOf(String preset) {
        List<Path> holding = sections().findAll { table, names ->
            preset in names
        }.keySet().toList()
        if (holding.size() != 1) {
            throw new IllegalArgumentException("stand-in preset '${preset}' has a section in ${holding.size()} tables, not one")
        }
        holding[0]
    }

    /** The rows of {@code preset}'s section, each split into its words; comments and blanks left out. */
    List<List<String>> rows(String preset) {
        boolean on = false
        List<List<String>> rows = []
        tableOf(preset).toFile().readLines().each { String line ->
            if (line ==~ /\[[^\]]+\]/) {
                on = line == "[${preset}]".toString()
            } else if (on && !line.isBlank() && !line.startsWith('#')) {
                rows << line.trim().split(/\s+/).toList()
            }
        }
        rows
    }

    /** Whether {@code preset} records, writes, reads a per-run file or takes the link's name. */
    boolean perRun(String preset) {
        rows(preset).any { List<String> row ->
            row.size() >= 2 && (row[1] in PER_RUN_ACTIONS || '@name' in row[0].split(',')
            || row.drop(2).any { it.startsWith('@') })
        }
    }

    /**
     * The answer {@code ref} names, {@code <file>#<answer>} under {@code data/}, as {@code stand-in.sh}
     * prints it: the lines of section {@code [answer]} up to the next section, less the one blank
     * line that separates them, each ended by a line break.
     */
    String answer(String ref) {
        int at = ref.indexOf('#')
        Path file = library.resolve('data').resolve(at < 0 ? ref : ref.substring(0, at))
        String name = at < 0 ? '' : ref.substring(at + 1)
        List<String> lines = Files.isRegularFile(file) ? file.toFile().readLines() : []
        int start = lines.indexOf("[${name}]".toString())
        if (at < 0 || start < 0) {
            throw new IllegalArgumentException("the stand-in library has no answer '${ref}'")
        }
        List<String> body = []
        for (String line : lines.drop(start + 1)) {
            if (line ==~ /\[[^\]]+\]/) {
                break
            }
            body << line
        }
        if (!body.isEmpty() && body.last().isEmpty()) {
            body.removeLast()
        }
        body.collect { it + '\n' }.join('')
    }

    /** The kind of binary {@code preset} stands in for: its table's name up to the first dash. */
    String kindOf(String preset) {
        tableOf(preset).fileName.toString().replaceFirst(/\.params$/, '').replaceFirst(/-.*$/, '')
    }
}
