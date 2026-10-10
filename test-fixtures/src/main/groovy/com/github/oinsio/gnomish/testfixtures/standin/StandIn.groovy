package com.github.oinsio.gnomish.testfixtures.standin

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one owner of stand-in binaries in test sources (ADR 0015, design D23 of
 * supervise-daemon-loops-and-embed-dashboard): a spec names a committed preset and receives the
 * path production runs in place of {@code git}, {@code docker}, the agent CLI or a hook. A preset
 * of the library {@code test-fixtures/src/main/resources/stand-in/} is a symbolic link
 * {@code links/<preset>} to {@code stand-in.sh} and a section of one of the tables under
 * {@code tables/} (several presets to a table, {@link StandInTables}); the script's header is the
 * grammar of the rows.
 *
 * <p>Nothing here writes an executable file. A preset is handed out by its committed link. The one
 * per-run artefact is a symbolic link to that link ({@link #recording}, {@link #link}): what the
 * stand-in records lands beside it as {@code <link>.log}, and what a spec writes beside it as
 * {@code <link>.<name>} is what the table's {@code @name} rows read. A link is not a new executable
 * file, so the operating system's first-run assessment of the script is paid once per checkout,
 * not once per test.
 *
 * <p>The library is found through the {@code standInDir} system property, which
 * {@code stand-in-conventions} hands every test JVM and every PIT minion: the scripts run from the
 * source tree, never from a copy a build or a jar would make.
 *
 * <p>Implements FR24, NFR-P2 of supervise-daemon-loops-and-embed-dashboard.
 */
final class StandIn {

    /** The system property naming the library directory. */
    static final String DIR_PROPERTY = 'standInDir'

    private StandIn() {
    }

    /** The library: {@code stand-in.sh}, {@code links/}, {@code tables/}, {@code data/}, {@code steps/}, {@code process/}. */
    static Path library() {
        String dir = System.getProperty(DIR_PROPERTY)
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("the ${DIR_PROPERTY} system property is not set; "
            + 'stand-in-conventions hands it to every Test task and to PIT')
        }
        Path.of(dir)
    }

    /** The library's index of presets and their rows. */
    static StandInTables tables() {
        new StandInTables(library())
    }

    /** The committed link of preset {@code id}, the path every run of it goes through. */
    static Path preset(String id) {
        Path link = library().resolve('links').resolve(id)
        if (!Files.isSymbolicLink(link)) {
            throw new IllegalArgumentException("no stand-in preset '${id}' under ${library()}")
        }
        link
    }

    /** A git preset, run through its committed link: it neither records nor reads per-run files. */
    static Path git(String id) {
        committed(id, 'git', 'action')
    }

    /** A docker preset, run through its committed link. */
    static Path docker(String id) {
        committed(id, 'docker')
    }

    /** An agent preset, run through its committed link. */
    static Path agent(String id) {
        committed(id, 'agent')
    }

    /**
     * A committed process-shaped fake under the library's {@code process/} directory: a child that
     * stalls, ignores a signal, forks or keeps a pipe open — behaviour that is the subject of the
     * supervisor specs and that no table expresses, so each is one reviewed script, run as it is.
     */
    static Path process(String name) {
        Path script = library().resolve('process').resolve("${name}.sh")
        if (!Files.isExecutable(script)) {
            throw new IllegalArgumentException("no committed process fake '${name}' under ${library()}")
        }
        script
    }

    /**
     * One answer of the library, {@code <file>#<answer>} under {@code data/}, exactly as the script
     * prints it — the text a spec asserts is read from here, never retyped.
     */
    static String data(String ref) {
        tables().answer(ref)
    }

    /**
     * A fresh per-run link in {@code dir} to preset {@code id}, named after it with a number
     * ({@code record-argv-1}, {@code record-argv-2}, …): two calls in one directory are two links,
     * two logs.
     */
    static Path recording(Path dir, String id) {
        Path target = preset(id)
        String name = target.fileName.toString()
        int n = 1
        while (true) {
            try {
                return Files.createSymbolicLink(dir.resolve("${name}-${n}"), target)
            } catch (FileAlreadyExistsException ignored) {
                n++
            }
        }
    }

    /**
     * A per-run link at exactly {@code at} to preset {@code id} — for a name the caller chooses: a
     * hook git runs by its name, a {@code git} looked up on {@code PATH}, or the value a id
     * takes from its link's name ({@code @name}, {@code export-name}). The directory must exist.
     */
    static Path link(Path at, String id) {
        Files.createSymbolicLink(at, preset(id))
    }

    /**
     * Points the per-run {@code link} at preset {@code id} instead, in one rename: a process the
     * production code starts later runs the new id, one already running keeps the old. This is
     * how a stand-in changes behaviour mid-spec (a failing probe that heals) without a marker file
     * its script would have to test.
     */
    static Path repoint(Path link, String id) {
        if (!Files.isSymbolicLink(link)) {
            throw new IllegalArgumentException("${link} is not a stand-in link")
        }
        Path next = link.resolveSibling("${link.fileName}.next")
        Files.deleteIfExists(next)
        Files.createSymbolicLink(next, preset(id))
        // rename(2) replaces the old link in one step. Not Files.move with its copy options: the
        // atomic-rename discipline belongs to :atomicfile (AtomicWriteBoundarySpec), and this is
        // a link swap, not a file write.
        if (!next.toFile().renameTo(link.toFile())) {
            throw new IllegalStateException("could not re-point ${link} at ${id}")
        }
        link
    }

    /** The per-run file {@code <link>.<name>} a table's {@code @name} reads. */
    static Path beside(Path link, String name) {
        link.resolveSibling("${link.fileName}.${name}")
    }

    /** The log a per-run link's {@code record} rows append to. */
    static Path log(Path link) {
        beside(link, 'log')
    }

    private static Path committed(String id, String... kinds) {
        Path link = preset(id)
        StandInTables tables = tables()
        if (!(tables.kindOf(id) in kinds)) {
            throw new IllegalArgumentException("stand-in preset '${id}' stands in for ${tables.kindOf(id)}, not ${kinds.join(' or ')}")
        }
        if (tables.perRun(id)) {
            throw new IllegalArgumentException("stand-in preset '${id}' records, reads per-run files or takes its link's name; "
            + 'take it through StandIn.recording or StandIn.link')
        }
        link
    }
}
