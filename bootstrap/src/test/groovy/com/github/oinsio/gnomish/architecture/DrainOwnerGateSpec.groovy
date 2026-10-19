package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * M12, M16 and design D15, D17 of fix-terminal-receipt-convergence: a child's output is joined
 * through one drain, {@code Drain} of {@code :subprocess}, with the post-exit bound its supervisor
 * derives — and the cut flag of a capture is judged by one owner, never read and dropped by a caller.
 *
 * <p><b>M12.</b> The six readers of a child's output — {@code CaptureRunner}, {@code GitExec},
 * {@code CapturedExec}, {@code BoundedTail}, {@code ContainerFileChannel}, {@code StreamDrain} (the
 * design's Drain row) — spell no unbounded join ({@code .join()} on a thread or a future) and no
 * {@code Duration.of…} literal, since the bound is the supervisor's or the handle's, never a
 * reader's own constant; and {@code ExecPipeDrain}, the second drain implementation task 8.12
 * deleted, appears nowhere. The allowlist of unbounded joins is empty: {@code BoundedTail}'s future
 * join, its last entry, left with task 8.15, which put the tail on the owner's drain joined with the
 * handle's bound, and {@code StreamDrain}'s {@code CLOSE_JOIN} left with task 8.14, so no reader
 * holds a bound of its own.
 *
 * <p><b>M16.</b> {@code Captured.truncated()} is read by no production source: {@code
 * CaptureRunner.await} turns a cut clean exit into {@code CutCaptureException} and every other
 * reader asks {@code CutCaptureException.refuses}, so a caller reading the flag is a caller deciding
 * the rule a second time (design D15 as amended 2026-10-17). The scan covers every production source
 * that names the {@code :subprocess} package — the only files that can hold a {@code Captured} — so
 * {@code BoardModel.truncated()} of the board, an unrelated window flag, is out of its scope by
 * construction; the scope is asserted to reach the files that once read the flag.
 *
 * <p>The owner of the pipe itself — no source outside {@code ChildOutput} obtains or reads a child's
 * stream, and no production source sleeps — is {@code ChildOutputOwnerGateSpec} (design D8 of
 * make-pipe-reads-non-parking).
 *
 * <p>Comments are stripped before every scan.
 */
class DrainOwnerGateSpec extends Specification {

    /**
     * The six readers of a child's output, each with how it takes the bytes from {@code ChildOutput},
     * the one owner of a child's readable pipe (design D1 of make-pipe-reads-non-parking). {@code
     * ChildOutputOwnerGateSpec} asserts its whole-tree scan reaches every one of them.
     */
    static final Map<String, String> READERS = [
        'subprocess/src/main/java/com/github/oinsio/gnomish/subprocess/CaptureRunner.java': 'drains stdout and stderr as ChildOutput on two Drains',
        'gitobjects/src/main/java/com/github/oinsio/gnomish/gitobjects/GitExec.java': 'drains stdout (capped or not) and stderr as ChildOutput on Drains',
        'sandbox/core/src/main/java/com/github/oinsio/gnomish/sandbox/CapturedExec.java': 'drains the ExecHandle\'s ChildOutput on one Drain',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/BoundedTail.java': 'keeps the tail of the ExecHandle\'s ChildOutput on one Drain',
        'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/ContainerFileChannel.java': 'drains the exec\'s stdout and stderr as ChildOutput on two Drains',
        'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/StreamDrain.java': 'reads lines from the ChildOutput\'s stream view, the owner\'s pull form',
    ]

    private static final Pattern UNBOUNDED_JOIN = ~/\.\s*join\s*\(\s*\)/
    private static final Pattern DURATION_LITERAL = ~/\bDuration\s*\.\s*of\w*\s*\(/
    private static final Pattern DELETED_DRAIN = ~/\bExecPipeDrain\b/
    private static final Pattern FLAG_READ = ~/(\.\s*truncated\s*\(\s*\)|::\s*truncated\b)/
    private static final Pattern SUBPROCESS_PACKAGE = ~/\bcom\.github\.oinsio\.gnomish\.subprocess\b/

    /** The files that read the flag before 8.10a, which the M16 scope must still reach. */
    private static final List<String> FORMER_FLAG_READERS = [
        'subprocess/src/main/java/com/github/oinsio/gnomish/subprocess/CaptureRunner.java',
        'adapters/git/src/main/java/com/github/oinsio/gnomish/adapter/git/GitProcessRunner.java',
        'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/DockerCli.java',
        'gitobjects/src/main/java/com/github/oinsio/gnomish/gitobjects/GitExec.java',
    ]

    // Scenario: lifecycle/subprocess-supervision / Every reader waits with the one bound
    def "M12: no reader of a child's output joins without a bound"() {
        expect:
        readers().findAll {
            UNBOUNDED_JOIN.matcher(code(it)).find()
        } == []
    }

    def "M12: no reader of a child's output holds a bound of its own"() {
        expect:
        readers().findAll {
            DURATION_LITERAL.matcher(code(it)).find()
        } == []
    }

    def "M12: the deleted second drain implementation appears in no source"() {
        given:
        def production = RepoSourceTree.productionSources()
        def tests = RepoSourceTree.testSources {
            !it.endsWith('/DrainOwnerGateSpec.groovy')
        }
        assert production.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        assert tests.size() >= RepoSourceTree.KNOWN_TEST_SOURCES

        expect:
        (production + tests).findAll {
            DELETED_DRAIN.matcher(RepoSourceTree.code(it)).find()
        }
        .collect { RepoSourceTree.relative(it) } == []
    }

    // FR17, M16: a caller that reads the flag decides the cut rule a second time.
    def "M16: no production source that can hold a capture reads its cut flag"() {
        given:
        def scope = RepoSourceTree.productionSources().findAll {
            SUBPROCESS_PACKAGE.matcher(RepoSourceTree.code(it)).find()
        }
        def reached = scope.collect { RepoSourceTree.relative(it) }

        expect: 'the scope reached every file that once read the flag, and none reads it now'
        reached.containsAll(FORMER_FLAG_READERS)
        scope.findAll { FLAG_READ.matcher(RepoSourceTree.code(it)).find() }
        .collect { RepoSourceTree.relative(it) } == []
    }

    def "the detectors: #shape"() {
        expect:
        pattern.matcher(RepoSourceTree.stripComments(source)).find() == detected

        where:
        shape | pattern | source || detected
        'a thread joined' | UNBOUNDED_JOIN | 'thread.join();' || true
        'a future joined, spaced' | UNBOUNDED_JOIN | 'future . join ( );' || true
        'a bounded join' | UNBOUNDED_JOIN | 'thread.join(bound);' || false
        'a string join' | UNBOUNDED_JOIN | 'String.join(",", parts);' || false
        'a commented join' | UNBOUNDED_JOIN | '// thread.join();' || false
        'a seconds literal' | DURATION_LITERAL | 'Duration.ofSeconds(1)' || true
        'a millis literal' | DURATION_LITERAL | 'Duration . ofMillis (5)' || true
        'a derived bound' | DURATION_LITERAL | 'supervisor.postExitBound()' || false
        'a flag read' | FLAG_READ | 'if (captured.truncated()) {' || true
        'a flag reference' | FLAG_READ | 'map(Captured::truncated)' || true
        'a different accessor' | FLAG_READ | 'captured.stdout()' || false
        'a commented read' | FLAG_READ | '/* captured.truncated() */' || false
    }

    /** The six readers, each asserted to exist so a moved file fails instead of leaving the scan. */
    private static List<String> readers() {
        READERS.keySet().each {
            assert new File(RepoSourceTree.repoRoot().toFile(), it).isFile(): "reader moved or deleted: $it"
        }
        READERS.keySet().sort()
    }

    private static String code(String relative) {
        RepoSourceTree.code(new File(RepoSourceTree.repoRoot().toFile(), relative))
    }
}
