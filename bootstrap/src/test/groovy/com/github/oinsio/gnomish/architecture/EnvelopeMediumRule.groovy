package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree

/**
 * The envelope-medium rule itself (FR8, FR9, UX2, M2 of fix-envelope-medium), held apart from the
 * gate that runs it: the tokens each rule rejects, the scan that finds them in a source, and the
 * failure text the build prints when one is found.
 *
 * <p>{@link EnvelopeMediumBoundarySpec} owns the owned trees, the allowlists and the whole-tree
 * assertions; {@link EnvelopeMediumDetectorSpec} drives this class over seeded violating lines.
 * Splitting it out is what makes UX2 assertable at all: over the production tree both rules find
 * nothing, so the message a maintainer would read is composed on a path no green run takes.
 */
final class EnvelopeMediumRule {

    private EnvelopeMediumRule() {
    }

    /**
     * The ten filesystem read calls of design D6. {@code isDirectory} is among them because it is
     * the one call that could test {@code worktree/.gnomish-task} without tripping a ban on {@code
     * exists}.
     */
    static final List<String> READ_CALLS = [
        'Files.readString(',
        'Files.exists(',
        'Files.notExists(',
        'Files.isRegularFile(',
        'Files.isDirectory(',
        'Files.readAllBytes(',
        'Files.newBufferedReader(',
        'Files.lines(',
        'Files.list(',
        'Files.walk(',
    ]

    /**
     * The literals rule 2 rejects. The directory is matched without its closing quote so that
     * {@code ".gnomish-task/"} and {@code ".gnomish-task/decisions"} are hits too.
     */
    static final List<String> PATH_LITERALS = [
        '".gnomish-task',
        '"task.json"',
        '"state.json"'
    ]

    private static final String READ_OWNER_HINT =
    'the envelope is read by GitTaskStore over GitShowTip at the worktree\'s HEAD, never from the working copy'

    private static final String PATH_OWNER_HINT =
    "the envelope's path names are declared once, by domain.branch.EnvelopePaths"

    /** The build failure a maintainer reads when rule 1 is broken (UX2). */
    static String readViolation(List<String> offenders) {
        "filesystem reads outside the allowlist — $READ_OWNER_HINT:\n" + offenders.join('\n')
    }

    /** The build failure a maintainer reads when rule 2 is broken (UX2). */
    static String pathViolation(List<String> offenders) {
        "envelope path literals outside the owner — $PATH_OWNER_HINT:\n" + offenders.join('\n')
    }

    /** Each token this file spells in code, reported as {@code path:line — token}. */
    static List<String> hits(File file, List<String> tokens) {
        hits(RepoSourceTree.relative(file), file.readLines(), tokens)
    }

    /** Each token these lines spell in code, reported as {@code path:line — token}. */
    static List<String> hits(String relative, List<String> lines, List<String> tokens) {
        def found = []
        lines.eachWithIndex { line, index ->
            def code = RepoSourceTree.codeOnly(line)
            tokens.each { token ->
                if (code.contains(token)) {
                    found << "$relative:${index + 1} — $token"
                }
            }
        }
        found
    }
}
