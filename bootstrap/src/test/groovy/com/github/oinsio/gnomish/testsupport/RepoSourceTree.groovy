package com.github.oinsio.gnomish.testsupport

import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate

/**
 * Shared repository-tree scanning for whole-tree architecture gates (RawCaptureGateSpec,
 * UntrustedTextSinkGateSpec, LogContractGateSpec, ProjectIdentityDerivationGateSpec,
 * DiscoveredRegistryOnlySpec, GithubPluginAbsenceSpec, ModuleBuildFileSpec,
 * ApiCompatibilityGateSpec and the boundary specs beside them — {@code grep -rl RepoSourceTree}
 * enumerates them): every one resolves the same {@code repoRoot} system property and scans the
 * same source tree, so the scan lives here once instead of once per gate.
 */
class RepoSourceTree {

    /**
     * A mis-resolved repoRoot, or a walk that stopped early, would make a source-tree gate pass
     * over a set far too small to have reached the tree. The floor is therefore near the real
     * count (1310 sources at the time of writing, 1211 for the narrowest filtered scan) rather
     * than at a token 100, which a scan reaching 8% of the build would still satisfy. It stays a
     * floor and is never pinned to the exact count, which every commit moves. Raise it when the
     * smallest filtered scan outgrows it; lower it only alongside a real deletion.
     */
    static final int KNOWN_PRODUCTION_SOURCES = 1000

    /** The repository root wired by bootstrap's {@code test} task (see verification.gradle). */
    static Path repoRoot() {
        def property = TestTaskProperty.required('repoRoot')
        def root = Path.of(property)
        assert Files.isDirectory(root): "repoRoot does not point at a directory: $property"
        root
    }

    /** Every production source of the build, optionally narrowed by a relative-path predicate. */
    static List<File> productionSources(Predicate<String> extraFilter = {
                true
            }) {
        sources('/src/main/', extraFilter)
    }

    /** A mis-resolved repoRoot would make a test-source gate pass over an empty file set. */
    static final int KNOWN_TEST_SOURCES = 100

    /**
     * Every test source of the build, optionally narrowed by a relative-path predicate. The log
     * contract gate (FR16) needs it: "a code no test source names" is a whole-tree question about
     * the test tree, the mirror of the production scan above.
     */
    static List<File> testSources(Predicate<String> extraFilter = {
                true
            }) {
        sources('/src/test/', extraFilter)
    }

    /**
     * Every {@code .java}/{@code .groovy} source under the given source-set marker, excluding
     * build output, narrowed by the caller's relative-path predicate.
     */
    private static List<File> sources(String sourceSetMarker, Predicate<String> extraFilter) {
        Files.walk(repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { repoRoot().relativize(it).toString() }
            .filter { it.contains(sourceSetMarker) }
            .filter { it.endsWith('.java') || it.endsWith('.groovy') }
            .filter { !buildOutput(it, sourceSetMarker) }
            .filter { extraFilter.test(it) }
            .map { repoRoot().resolve(it).toFile() }
            .toList()
        }
    }

    /**
     * Whether a path is a build-output copy of a source rather than the source itself. Gradle and
     * Spotless mirror whole source sets under a module's {@code build} directory, so
     * {@code adapters/git/build/spotless-clean/spotlessJava/src/main/java/Foo.java} matches the
     * source-set marker exactly as the original does — and a whole-tree gate would then see one
     * offending line twice, the second time at a path no allowlist can name, because the copy is
     * created and deleted by whichever Gradle task happened to run last.
     *
     * <p>Only the module prefix — the part before the source-set marker — is examined. A plain
     * {@code contains('/build/')} would also drop real sources: {@code build} is a package name
     * here ({@code build-logic/src/main/groovy/com/github/oinsio/gnomish/build/}), and
     * {@code build-logic} is a real module that every gate must keep scanning.
     */
    private static boolean buildOutput(String relativePath, String sourceSetMarker) {
        relativePath.substring(0, relativePath.indexOf(sourceSetMarker)).tokenize('/').contains('build')
    }

    /**
     * A file's source with every comment removed: what the compiler actually sees. One pass over
     * the whole file, not line by line: a line-local stripper cannot know whether a line sits inside
     * a block comment, so it guessed from the line's first character — dropping code that follows a
     * leading {@code /* … *\/} and every continuation line the formatter opens with a {@code *}
     * operator (a blind spot in every gate at once), while scanning a trailing block comment and the
     * lines of a block that do not open with {@code *}. Comment openers inside string, character and
     * text-block literals are literal text. Line breaks are kept, so line structure survives.
     */
    static String code(File file) {
        stripComments(file.text)
    }

    /** {@link #code} over a source held in memory. */
    static String stripComments(String source) {
        def out = new StringBuilder(source.length())
        int i = 0
        int n = source.length()
        while (i <n) {
            if (source.startsWith('//', i)) {
                while (i <n && source.charAt(i) != '\n' as char) {
                    i++
                }
            } else if (source.startsWith('/*', i)) {
                int end = source.indexOf('*/', i + 2)
                int stop = end < 0 ? n : end + 2
                source.substring(i, stop).each {
                    if (it == '\n') {
                        out.append('\n')
                    }
                }
                i = stop
            } else if (source.startsWith('"""', i)) {
                int end = source.indexOf('"""', i + 3)
                int stop = end < 0 ? n : end + 3
                out.append(source, i, stop)
                i = stop
            } else if (source.charAt(i) == '"' as char || source.charAt(i) == '\'' as char) {
                int stop = literalEnd(source, i)
                out.append(source, i, stop)
                i = stop
            } else {
                out.append(source.charAt(i))
                i++
            }
        }
        out.toString()
    }

    /** The index just past the string or character literal opening at {@code start}, or the line's end. */
    private static int literalEnd(String source, int start) {
        char quote = source.charAt(start)
        int i = start + 1
        while (i <source.length()) {
            char c = source.charAt(i)
            if (c == '\\' as char) {
                i += 2
            } else if (c == quote) {
                return i + 1
            } else if (c == '\n' as char) {
                return i
            } else {
                i++
            }
        }
        source.length()
    }

    /**
     * A line's source with its comment removed: what the compiler actually sees.
     *
     * <p>The {@code //} that opens a comment is found outside string and character literals only.
     * A blind {@code replaceFirst('//.*', '')} would cut a line like {@code log.warn("see
     * https://host {}", x)} in the middle of its literal, leaving an unbalanced quote that the
     * call-site parser cannot close — and a gate scanning what is left would silently drop the
     * site instead of judging it.
     */
    static String codeOnly(String line) {
        def trimmed = line.trim()
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) {
            return ''
        }
        line.substring(0, commentStart(line))
    }

    /** Index of the {@code //} that opens this line's trailing comment, or the line's length. */
    private static int commentStart(String line) {
        int quote = -1
        for (int i = 0; i <line.length(); i++) {
            int c = line.charAt(i) as int
            if (quote >= 0) {
                if (c == ('\\' as char) as int) {
                    i++
                } else if (c == quote) {
                    quote = -1
                }
            } else if (c == ('"' as char) as int || c == ('\'' as char) as int) {
                quote = c
            } else if (c == ('/' as char) as int && i + 1 <line.length() && line.charAt(i + 1) == '/' as char) {
                return i
            }
        }
        line.length()
    }

    /** A file's path relative to the repository root. */
    static String relative(File file) {
        repoRoot().relativize(file.toPath()).toString()
    }
}
