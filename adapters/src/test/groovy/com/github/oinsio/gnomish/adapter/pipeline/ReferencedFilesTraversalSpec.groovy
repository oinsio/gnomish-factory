package com.github.oinsio.gnomish.adapter.pipeline

import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.escapingCriteria
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.escapingInstructions
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.judge
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.stage

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Path traversal in referenced files (task 6.4): ReferencedFiles delegates the
 * resolve-within-root decision to PathSafety, so a reference that escapes the .gnomish/
 * root — via {@code ../}, via an absolute path, or via a symlink whose real target lies
 * outside — is reported as a located traversal error instead of being read.
 *
 * <p>Traversal is checked first: an escaping reference never has its existence checked,
 * so a single reference never yields both a traversal and a "does not exist" error. The
 * existence semantics themselves are specified in {@link ReferencedFilesSpec} (FR6).
 *
 * <p>Implements NFR-S2 of load-pipeline-config.
 */
class ReferencedFilesTraversalSpec extends Specification implements GnomishTreeWriter {

    @TempDir
    Path root

    def "a traversing instructions ref is rejected as escaping the root, not existence-checked (NFR-S2)"() {
        expect: 'the escape is reported as traversal; existence of the outside file is never checked'
        ReferencedFiles.check(root, [stage('plan', ref)]) == [
            escapingInstructions('plan', ref)
        ]

        where:
        ref << [
            '../outside.md',
            '../../etc/passwd',
            '/etc/passwd'
        ]
    }

    def "a traversing judge criteria ref is rejected as escaping the root (NFR-S2)"() {
        given: 'instructions exist so the only reported problem is the escaping criteria'
        write('stages/plan/instructions.md', 'do it\n')

        expect:
        ReferencedFiles.check(root, [
            stage('plan', 'stages/plan/instructions.md', [judge(ref)])
        ]) ==
        [
            escapingCriteria('plan', 0, ref)
        ]

        where:
        ref << [
            '../outside.md',
            '/etc/passwd'
        ]
    }

    def "an escaping ref is reported as traversal only, never also as does-not-exist (NFR-S2)"() {
        given: 'both refs escape via ..; neither file exists under the root'
        def stages = [
            stage('plan', '../outside.md', [judge('../gone.md')])
        ]

        expect: 'exactly the two traversal errors — no existence error is added for the same refs'
        ReferencedFiles.check(root, stages) == [
            escapingInstructions('plan', '../outside.md'),
            escapingCriteria('plan', 0, '../gone.md')
        ]
    }

    @IgnoreIf({ !PathSafetySpec.symlinksSupported() })
    def "a symlink whose target is outside the root is rejected as escaping (NFR-S2)"() {
        given: 'a file OUTSIDE the root, a symlink INSIDE .gnomish/ pointing at it'
        Path outsideDir = Files.createTempDirectory('gnomish-outside')
        Path outside = Files.writeString(outsideDir.resolve('secret.md'), 'secret\n')
        Files.createDirectories(root.resolve('stages/plan'))
        Files.createSymbolicLink(root.resolve('stages/plan/instructions.md'), outside)

        expect: 'the real path escapes the root, so the reference is rejected as traversal'
        ReferencedFiles.check(root, [
            stage('plan', 'stages/plan/instructions.md')
        ]) ==
        [
            escapingInstructions('plan', 'stages/plan/instructions.md')
        ]

        cleanup:
        Files.deleteIfExists(outside)
        Files.deleteIfExists(outsideDir)
    }

    @IgnoreIf({ !PathSafetySpec.symlinksSupported() })
    def "a symlink whose target stays within the root is allowed and existence-checked (NFR-S2)"() {
        given: 'a real file inside the root and a symlink inside the root pointing at it'
        Files.createDirectories(root.resolve('stages/plan'))
        Path realFile = Files.writeString(root.resolve('stages/plan/real.md'), 'ok\n')
        Files.createSymbolicLink(root.resolve('stages/plan/instructions.md'), realFile)

        expect: 'a within-root symlink is not an escape and its target exists — no error'
        ReferencedFiles.check(root, [
            stage('plan', 'stages/plan/instructions.md')
        ]).isEmpty()
    }
}
