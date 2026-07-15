package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.adapter.pipeline.PathSafety.Escapes
import com.github.oinsio.gnomish.adapter.pipeline.PathSafety.Within
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * PathSafety is the loader's path-traversal guard (task 6.4, NFR-S2): given the
 * {@code .gnomish/} root and a referenced-file path from a manifest, it resolves the
 * reference against the root and rejects anything that escapes the root, before any
 * existence check reads the outside file. Two escape mechanisms are covered:
 *
 * <ul>
 *   <li>lexical escape — {@code ../} traversal or an absolute path — caught by
 *       normalizing the resolved path and asserting it stays under the root; this needs
 *       no file to exist;</li>
 *   <li>symlink escape — a link inside the root whose real target is outside — caught by
 *       resolving symlinks ({@code toRealPath}) and asserting the real path stays under
 *       the root's real path; this only matters when the link exists.</li>
 * </ul>
 *
 * A safe reference resolves to a {@code Within} carrying the resolved path (which
 * ReferencedFiles then existence-checks, task 6.3). An escaping reference resolves to
 * {@code Escapes} — the outside file is never opened or existence-checked.
 *
 * Implements NFR-S2 of load-pipeline-config.
 */
class PathSafetySpec extends Specification {

    @TempDir
    Path root

    def "a plain relative reference within the root is Within and carries the resolved path"() {
        expect:
        PathSafety.resolveWithinRoot(root, 'stages/plan/instructions.md') ==
                new Within(root.resolve('stages/plan/instructions.md').normalize())
    }

    def "a nested-but-normalized reference that stays within the root is Within"() {
        expect: 'the .. cancels a segment but never climbs above the root'
        PathSafety.resolveWithinRoot(root, 'stages/plan/../plan/instructions.md') ==
                new Within(root.resolve('stages/plan/instructions.md').normalize())
    }

    def "a reference does not need to exist to be judged Within (lexical only)"() {
        expect: 'no file created; a within-root ref is still Within'
        PathSafety.resolveWithinRoot(root, 'stages/plan/absent.md') instanceof Within
    }

    def "a non-normalized root is normalized before the containment check"() {
        given: 'a root path with a redundant sub/.. that points back at the temp root'
        Path nonNormalizedRoot = root.resolve('sub').resolve('..')

        expect: 'a within-root ref is still Within, carrying the fully normalized path'
        PathSafety.resolveWithinRoot(nonNormalizedRoot, 'a.md') ==
                new Within(root.resolve('a.md').normalize())
    }

    def "lexical escapes are rejected as Escapes without touching the outside file"() {
        expect:
        PathSafety.resolveWithinRoot(root, ref) == new Escapes(ref)

        where:
        ref << [
            '../outside.md',
            '../../etc/passwd',
            'stages/../../outside.md',
            '/etc/passwd'
        ]
    }

    @IgnoreIf({ !symlinksSupported() })
    def "a symlink whose real target is outside the root is Escapes"() {
        given: 'a file OUTSIDE the root, and a symlink INSIDE the root pointing at it'
        Path outsideDir = Files.createTempDirectory('gnomish-outside')
        Path outside = Files.writeString(outsideDir.resolve('secret.md'), 'secret\n')
        Files.createDirectories(root.resolve('stages/plan'))
        Path link = root.resolve('stages/plan/instructions.md')
        Files.createSymbolicLink(link, outside)

        expect: 'the real path escapes the root, so the reference is rejected'
        PathSafety.resolveWithinRoot(root, 'stages/plan/instructions.md') ==
                new Escapes('stages/plan/instructions.md')

        cleanup:
        Files.deleteIfExists(outside)
        Files.deleteIfExists(outsideDir)
    }

    @IgnoreIf({ !symlinksSupported() })
    def "a symlink whose real target stays within the root is Within"() {
        given: 'a real file inside the root and a symlink inside the root pointing at it'
        Files.createDirectories(root.resolve('stages/plan'))
        Path realFile = Files.writeString(root.resolve('stages/plan/real.md'), 'ok\n')
        Path link = root.resolve('stages/plan/instructions.md')
        Files.createSymbolicLink(link, realFile)

        expect:
        PathSafety.resolveWithinRoot(root, 'stages/plan/instructions.md') instanceof Within
    }

    @IgnoreIf({ !symlinksSupported() })
    def "a broken symlink loop that cannot be canonicalized is refused as Escapes"() {
        given: 'two symlinks inside the root pointing at each other — toRealPath cannot resolve them'
        Files.createDirectories(root.resolve('stages/plan'))
        Path a = root.resolve('stages/plan/a.md')
        Path b = root.resolve('stages/plan/b.md')
        Files.createSymbolicLink(a, b)
        Files.createSymbolicLink(b, a)

        expect: 'the guard refuses to vouch for a path it cannot fully resolve'
        PathSafety.resolveWithinRoot(root, 'stages/plan/a.md') ==
                new Escapes('stages/plan/a.md')
    }

    static boolean symlinksSupported() {
        Path probeDir = Files.createTempDirectory('gnomish-symlink-probe')
        try {
            Files.createSymbolicLink(probeDir.resolve('link'), probeDir.resolve('target'))
            return true
        } catch (UnsupportedOperationException | IOException ignored) {
            return false
        } finally {
            Files.walk(probeDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
