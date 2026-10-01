package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR3, design D9 of add-project-registry (single-owner row 3): the directory a command works in is
 * resolved once per process, by the configuration loader, into the {@code RegisteredClone} every
 * consumer takes. {@code ArgumentsParsingSupport.projectDir} and {@code requiredProjectDir} — the
 * one place a {@code --dir} value becomes a path — are therefore called by the loader and by
 * {@code project add}, which registers a path rather than looking one up, and by nothing else: a
 * command that resolves its own directory again can work in a clone the registry never matched.
 *
 * <p>A whole-tree question, so it lives in {@code :bootstrap}, the module that sees every layer;
 * the scan asserts it reached the whole production tree and every allowlisted file, the shape
 * {@link BaseHeadDefaultBoundarySpec} uses.
 */
class ProjectDirResolutionBoundarySpec extends Specification {

    private static final String SUPPORT =
    'application/src/main/java/com/github/oinsio/gnomish/app/ArgumentsParsingSupport.java'

    /** The owners of the one resolution, and the one exemption (design, single-owner row 3). */
    private static final Map<String, String> CALLERS = [
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/OperatorConfigLoader.java':
        'the owner: resolves the directory once, before any bean exists',
        'application/src/main/java/com/github/oinsio/gnomish/app/ProjectArgumentsParser.java':
        'project add: registers a path rather than looking one up',
    ]

    // FR3, D9: no production source outside the allowlist resolves --dir itself
    def "FR3: projectDir and requiredProjectDir are called only by the loader and project add"() {
        given: 'every production source, comments stripped'
        def sources = RepoSourceTree.productionSources()
        def callers = sources.findAll { resolvesDir(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) }
        .findAll { it != SUPPORT }
        .sort()

        expect: 'the scan reached the whole production tree'
        sources.size() > RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        and: 'every allowlisted caller really calls it — a stale entry would permit a file for nothing'
        callers.containsAll(CALLERS.keySet())

        and: 'nothing else does'
        callers == CALLERS.keySet().toSorted()
    }

    // Over a clean tree the detector never fires; a seeded call must be found, a comment must not.
    def "the detector finds a seeded resolution and leaves a commented one alone: #shape"() {
        expect:
        resolvesDir(RepoSourceTree.codeOnly(source)) == detected

        where:
        shape | source || detected
        'optional dir' | 'Path dir = ArgumentsParsingSupport.projectDir(args);' || true
        'required dir' | 'Path dir = ArgumentsParsingSupport.requiredProjectDir(args, "status");' || true
        'a trailing comment' | 'Path dir = clone.clonePath(); // was projectDir(args)' || false
        'the clone path' | 'Path dir = clone.clonePath();' || false
    }

    private static boolean resolvesDir(String code) {
        code =~ /\b(projectDir|requiredProjectDir)\s*\(/
    }
}
