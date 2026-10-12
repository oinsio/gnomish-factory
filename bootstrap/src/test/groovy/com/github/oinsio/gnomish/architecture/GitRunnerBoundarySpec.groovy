package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR27, M13 of supervise-daemon-loops-and-embed-dashboard (design D27, single-owner row 13): every
 * holder of the git runner takes the role, {@code GitRunner}, so its specs can hand it the
 * in-process fake ({@code ScriptedGit}) instead of a stand-in binary. Only the composition side
 * names the concrete {@code GitProcessRunner} as a field or parameter type: {@code :bootstrap},
 * which constructs it and cannot name the package-private role. {@code GitProcessRunner.java}
 * itself declares no holder, so it needs no row.
 *
 * <p>A holder retyped back to the class would compile and pass every spec — the class still
 * implements the role — and quietly force its specs back onto a process per git call. The allowlist
 * is paths, and the scan asserts it reached every one, so a moved composition file fails here
 * instead of widening the gate (the shape of {@link ClaimlessGitBoundarySpec}).
 */
class GitRunnerBoundarySpec extends Specification {

    /** The trees that hold a runner: the adapter's own package, the application layer, the root. */
    private static final List<String> SCANNED_TREES = [
        'adapters/git/src/main/',
        'application/src/main/',
        'bootstrap/src/main/',
    ]

    /** {@code GitProcessRunner} declared as the type of a field, a parameter or a local. */
    static final Pattern CONCRETE_HOLDER = ~/\bGitProcessRunner\s+[a-z]\w*\s*[,);=]/

    /**
     * The composition side: the Spring bean that builds the one runner and the container-mode
     * assembly that builds its own and hands it to the adapter's constructors.
     */
    private static final List<String> COMPOSITION = [
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ContainerRunSupport.java',
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ContainerTipReader.java',
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ManualRunConfiguration.java',
        'bootstrap/src/main/java/com/github/oinsio/gnomish/app/SandboxLifecyclePassFactory.java',
    ]

    // Scenario: build/quality-gates / A holder typed as the class fails the gate
    def "FR27, M13: only the composition side holds the concrete git runner; every other holder takes the role"() {
        given: 'every production source of the scanned trees, comments stripped'
        def sources = RepoSourceTree.productionSources { path ->
            SCANNED_TREES.any { path.startsWith(it) }
        }
        def holders = sources.findAll { File file ->
            CONCRETE_HOLDER.matcher(RepoSourceTree.code(file)).find()
        }.collect { RepoSourceTree.relative(it) }.toSet()

        expect: 'the scan reached the adapter tree and every allowlisted file'
        sources.any {
            RepoSourceTree.relative(it).endsWith('adapter/git/GitRunner.java')
        }
        holders.containsAll(COMPOSITION)

        and: 'no other source names the class as a holder type'
        (holders - COMPOSITION) == [] as Set
    }

    // Scenario: build/quality-gates / A holder typed as the class fails the gate
    def "FR27: the detector sees a field, a parameter and a local, and not a construction or a link"() {
        expect:
        CONCRETE_HOLDER.matcher(line).find() == holds

        where:
        line || holds
        'private final GitProcessRunner runner;' || true
        'Foo(GitProcessRunner runner, Path dir) {' || true
        'GitProcessRunner runner = build();' || true
        'var runner = new GitProcessRunner(timeout);' || false
        'LoggerFactory.getLogger(GitProcessRunner.class);' || false
        'private final GitRunner runner;' || false
    }
}
