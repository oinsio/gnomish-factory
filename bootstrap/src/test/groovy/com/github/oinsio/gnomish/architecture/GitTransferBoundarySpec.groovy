package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, M1, M3, design D7 of own-git-transfer-argv: every git transfer the factory runs takes its
 * argv from the one owner, {@code GitTransfer} in {@code :gittransfer}. No production source
 * outside the owner spells a transfer subcommand — as a git argument literal ({@code "fetch"},
 * {@code "clone"}, {@code "pull"}, {@code "submodule", "update"}, {@code "remote", "update"}) or
 * inside a shell script literal ({@code git fetch}, {@code git clone}, ...). A whole-tree question,
 * so it lives in {@code :bootstrap} like {@link BaseHeadDefaultBoundarySpec}, whose comment-stripped
 * scan it reuses: on the tree this gate was written against, fourteen surviving production files
 * name {@code git fetch} / {@code git clone} in javadoc, and a raw text scan would red on prose.
 *
 * <p>The reach half (FR9's second clause): the scanned source roots are enumerated from the
 * build's own {@code settings.gradle}, and the gate asserts the two sets are equal — a module
 * added to the build cannot fall outside the scan, and a module whose sources the walk missed
 * cannot pass it. This is what a hand-kept file list can never say ("did the list keep up",
 * the lesson recorded in {@code BaseHeadDefaultBoundarySpec}'s javadoc).
 *
 * <p>Over a clean tree the scan finds nothing, so a broken detector would look exactly like a
 * clean tree: the seeded scenarios below are what tell the two apart.
 */
class GitTransferBoundarySpec extends Specification {

    /** The git argument-literal shapes a transfer takes when built as an argv. */
    private static final List<String> ARGUMENT_LITERALS = [
        '"fetch"',
        '"clone"',
        '"pull"',
        '"submodule", "update"',
        '"remote", "update"',
    ]

    /** The shell script-literal shapes a transfer takes when rendered into a text block. */
    private static final List<String> SCRIPT_LITERALS = [
        'git fetch',
        'git clone',
        'git pull',
        'submodule update',
        'remote update',
    ]

    /** The owner: every file of the {@code :gittransfer} leaf may spell a transfer subcommand. */
    private static final String OWNER_ROOT = 'gittransfer/src/main/'

    /**
     * The two classifiers beside the runner: they name the subcommands in a {@code case} arm so
     * the runner can refuse an unowned transfer, bound a network command and lock a mutating one.
     * They read the token; they do not build it — an allowlist entry, not an exemption.
     */
    private static final List<String> CLASSIFIERS = [
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitNetworkCommands.java',
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitProcessRunner.java',
    ]

    @TempDir
    Path tempDir

    // FR9, M1, M3: a transfer literal outside the owner is a second argv builder — the shape
    //     the origin fetch builder and the literal harvest argv had before this change, whose flags then
    //     drifted apart (the 2026-09-13 reproductions).
    def "FR9, M1, M3: no production source outside the owner spells a transfer subcommand"() {
        given: 'every production source of the build, comments stripped'
        def sources = RepoSourceTree.productionSources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'every file that spells a transfer subcommand is the owner or a classifier'
        offenders(sources) == []

        and: 'and the allowlist is not stale: the owner and each classifier really spell one'
        allowlisted(sources).any {
            RepoSourceTree.relative(it).startsWith(OWNER_ROOT)
        }
        allowlisted(sources).collect {
            RepoSourceTree.relative(it)
        }.containsAll(CLASSIFIERS)
    }

    // FR9's reach clause: "every production source root" is a claim only the build's own module
    //     list can back — a new module falls inside the scan by construction, and a walk that
    //     missed one reds here rather than passing over the rest.
    def "FR9, M1: the scanned source roots are exactly the build's module set"() {
        given: 'the modules settings.gradle declares, main build and included build alike'
        def declared = declaredModules()

        and: 'the roots the production scan reached'
        def scanned = RepoSourceTree.productionSources()
                .collect {
                    RepoSourceTree.relative(it).takeBefore('/src/main/')
                }
                .toSet()

        expect: 'a declared module holding no production source would be the first gap'
        declared == scanned
    }

    // The detector is the gate — over a clean tree it never fires, so nothing above proves it
    //     would. A seeded argv must be found, and a javadoc or comment that names the same
    //     subcommand must not be: a gate that flagged prose would be deleted rather than obeyed.
    def "the detector finds a seeded transfer and leaves a commented one alone: #shape"() {
        expect:
        spellsTransfer(source) == detected

        where:
        shape | source || detected
        'a fetch argv' | 'runner.run(cloneDir, "fetch", "origin", refspec);' || true
        'a clone argv' | 'return List.of("clone", "--no-tags", url);' || true
        'a pull argv' | 'git.run(dir, "pull");' || true
        'a submodule update argv' | 'run(dir, "submodule", "update", "--init");' || true
        'a remote update argv' | 'run(dir, "remote", "update");' || true
        'a scripted clone' | '        git clone --no-local "$SEED" "$WORK"' || true
        'a scripted fetch' | '        git fetch origin "$1"' || true
        'a classifier case arm' | 'case "fetch", "push" -> true;' || true
        'a javadoc mention' | ' * The narrow fetch uses {@code git fetch origin <branch>}.' || false
        'a line comment' | '// the seed script ran git clone before this change' || false
        'a trailing comment' | 'var argv = transfer.argv(); // never "fetch" by hand' || false
        'a prose label' | 'return fetch.failureDetail("narrow fetch");' || false
        'the network read' | 'runner.run(dir, "ls-remote", "--heads", "origin");' || false
    }

    // The same judgement over a whole file, through the comment-stripped reader the gate uses:
    //     a seeded source outside the owner is reported by its path, and a file that only
    //     documents the subcommand is not.
    def "a seeded production file is reported, a documenting one is not"() {
        given: 'a file that builds a fetch argv, and one whose javadoc names the subcommand'
        def violating = tempDir.resolve('Violating.java')
        Files.writeString(violating, 'class Violating {\n    void go() {\n        run("fetch", "origin");\n    }\n}\n')
        def documenting = tempDir.resolve('Documenting.java')
        Files.writeString(documenting, '/**\n * Runs {@code git fetch} through the owner.\n */\nclass Documenting {}\n')

        expect:
        spellsTransfer(RepoSourceTree.code(violating.toFile()))
        !spellsTransfer(RepoSourceTree.code(documenting.toFile()))
    }

    /** The files outside the allowlist that spell a transfer subcommand in code, as the gate reports them. */
    private static List<String> offenders(List<File> sources) {
        sources.findAll {
            !allowlisted(it) && spellsTransfer(RepoSourceTree.code(it))
        }
        .collect { RepoSourceTree.relative(it) }
        .sort()
    }

    /** The allowlisted files that spell one — the evidence that each allowlist entry is live. */
    private static List<File> allowlisted(List<File> sources) {
        sources.findAll {
            allowlisted(it) && spellsTransfer(RepoSourceTree.code(it))
        }
    }

    private static boolean allowlisted(File file) {
        def relative = RepoSourceTree.relative(file)
        relative.startsWith(OWNER_ROOT) || CLASSIFIERS.contains(relative)
    }

    /**
     * Whether this source spells a transfer subcommand. Comments are already stripped by {@link
     * RepoSourceTree#code} for a whole file; the seeded scenario strips its own line, so the same
     * judgement runs on both.
     */
    private static boolean spellsTransfer(String source) {
        source.readLines().any { line ->
            def code = RepoSourceTree.codeOnly(line)
            ARGUMENT_LITERALS.any {
                code.contains(it)
            } || SCRIPT_LITERALS.any {
                code.contains(it)
            }
        }
    }

    /**
     * The module directories {@code settings.gradle} declares: every {@code include 'a:b'} as
     * {@code a/b}, plus every {@code includeBuild} — the build-logic plugins are production
     * source too, and the shared walk already reaches them.
     */
    private static Set<String> declaredModules() {
        def settings = RepoSourceTree.repoRoot().resolve('settings.gradle').toFile().readLines()
        def included = settings.collect { RepoSourceTree.codeOnly(it).trim() }
        .findAll {
            it.startsWith("include '") || it.startsWith("includeBuild '")
        }
        .collect {
            it.replaceFirst(/^include(Build)? '([^']+)'$/, '$2').replace(':', '/')
        }
        assert included.size() >= 15: "settings.gradle lists fewer modules than the build has: $included"
        included.toSet()
    }
}
