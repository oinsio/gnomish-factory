package com.github.oinsio.gnomish.adapter.pipeline

import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.command
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.judge
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.missingCriteria
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.missingInstructions
import static com.github.oinsio.gnomish.adapter.pipeline.ReferencedFilesFixtures.stage

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * ReferencedFiles is the loader's referenced-file existence check (task 6.3, FR6):
 * given the .gnomish/ root and the mapped domain stages, it confirms that every
 * non-blank referenced file — a stage's instructions.md and every judge check's
 * acceptance-criteria file — exists on disk as a regular file, resolved relative to
 * the root. Each miss is a located ConfigError (NFR-O1, UX2) on the referencing
 * stage's manifest, naming the missing path. It is I/O-bound (it needs the real
 * filesystem) so it lives in the adapter tier, not the pure domain (design D6).
 *
 * Existence semantics: the reference must resolve to a regular file. A missing path,
 * and a path that is a directory rather than a file, both fail. Contents are never
 * read (NG7 — no gradeability check); only existence.
 *
 * Blank-reference contract: a blank instructionsRef or judge criteriaFile is a
 * presence concern owned upstream (instructions presence by StructuralValidation,
 * task 5.2), never resolved here — a blank path must never be treated as "exists"
 * (it would falsely resolve to the root directory). So this check only inspects
 * non-blank references.
 *
 * Path traversal (../ escaping the root) is task 6.4 and is specified separately, in
 * {@link ReferencedFilesTraversalSpec} (NFR-S2); this spec assumes every reference it
 * builds resolves within the root.
 * Implements FR6 of load-pipeline-config.
 */
class ReferencedFilesSpec extends Specification implements GnomishTreeWriter {

    @TempDir
    Path root

    def "all referenced files present yields no errors"() {
        given: 'a stage whose instructions file and both judge criteria files exist on disk'
        write('stages/plan/instructions.md', 'do the thing\n')
        write('stages/plan/accept-a.md', 'criteria a\n')
        write('stages/plan/accept-b.md', 'criteria b\n')
        def stages = [
            stage('plan', 'stages/plan/instructions.md', [
                judge('stages/plan/accept-a.md'),
                command(),
                judge('stages/plan/accept-b.md')
            ] as List<VerifyCheck>)
        ]

        expect:
        ReferencedFiles.check(root, stages).isEmpty()
    }

    def "each single miss yields its exact located error"() {
        given:
        write('stages/plan/instructions.md', 'do it\n')
        write('stages/plan/accept.md', 'criteria\n')
        // A directory sitting where a file reference points is not a regular file.
        Files.createDirectories(root.resolve('stages/plan/as-dir.md'))

        when:
        def errors = ReferencedFiles.check(root, [built])

        then:
        errors == [expected]

        where:
        scenario | built || expected
        'missing instructions' | stage('plan', 'stages/plan/absent.md') || missingInstructions('plan', 'stages/plan/absent.md')
        'instructions is a directory' | stage('plan', 'stages/plan/as-dir.md') || missingInstructions('plan', 'stages/plan/as-dir.md')
        'missing judge criteria' | stage('plan', 'stages/plan/instructions.md', [
            judge('stages/plan/absent.md')
        ]) || missingCriteria('plan', 0, 'stages/plan/absent.md')
        'judge criteria is a directory' | stage('plan', 'stages/plan/instructions.md', [
            judge('stages/plan/as-dir.md')
        ]) || missingCriteria('plan', 0, 'stages/plan/as-dir.md')
    }

    def "a stage with no judge checks reports no criteria errors"() {
        given: 'instructions exists; the only checks are non-judge'
        write('stages/plan/instructions.md', 'do it\n')
        def stages = [
            stage('plan', 'stages/plan/instructions.md', [
                command(),
                new VerifyCheck.Builtin('files_exist', [:]),
                new VerifyCheck.External('ci', 'github', Duration.ofSeconds(5), Duration.ofSeconds(60), VerifyCheck.TimeoutClass.QUALITY)
            ] as List<VerifyCheck>)
        ]

        expect:
        ReferencedFiles.check(root, stages).isEmpty()
    }

    def "multiple judges in one stage: only the missing one is reported, at its verify index"() {
        given: 'the first judge criteria exists, the third is missing; index 1 is a non-judge'
        write('stages/plan/instructions.md', 'do it\n')
        write('stages/plan/present.md', 'criteria\n')
        def stages = [
            stage('plan', 'stages/plan/instructions.md', [
                judge('stages/plan/present.md'),
                command(),
                judge('stages/plan/gone.md')
            ] as List<VerifyCheck>)
        ]

        expect: 'exactly the missing judge, located at verify index 2'
        ReferencedFiles.check(root, stages) == [
            missingCriteria('plan', 2, 'stages/plan/gone.md')
        ]
    }

    def "a blank reference is not checked for existence (presence is an upstream concern)"() {
        given: 'a stage with a blank instructions ref and a judge with a blank criteria ref'
        def stages = [
            stage('plan', '   ', [judge('')])
        ]

        expect: 'no existence error is raised for either blank ref — blank never resolves to the root'
        ReferencedFiles.check(root, stages).isEmpty()
    }

    def "problems across stages are aggregated in pipeline order, instructions before criteria within a stage"() {
        given: 'plan misses its instructions and a judge; build is fully present'
        write('stages/build/instructions.md', 'build it\n')
        write('stages/build/accept.md', 'criteria\n')
        def stages = [
            stage('plan', 'stages/plan/absent.md', [
                judge('stages/plan/gone.md')
            ]),
            stage('build', 'stages/build/instructions.md', [
                judge('stages/build/accept.md')
            ])
        ]

        expect: 'plan errors first (instructions then criteria), then build has none'
        ReferencedFiles.check(root, stages) == [
            missingInstructions('plan', 'stages/plan/absent.md'),
            missingCriteria('plan', 0, 'stages/plan/gone.md')
        ]
    }

    def "the returned list is immutable"() {
        when:
        ReferencedFiles.check(root, []).add(new ConfigError('x', 'y', 'z'))

        then:
        thrown(UnsupportedOperationException)
    }

    def "checking does not create, modify, or delete anything under the root"() {
        given: 'a tree with a present instructions file'
        write('stages/plan/instructions.md', 'do it\n')
        def stages = [
            stage('plan', 'stages/plan/instructions.md')
        ]
        def before = snapshot()

        when:
        ReferencedFiles.check(root, stages)

        then: 'nothing on disk changed (NFR-R1: read-only)'
        snapshot() == before
    }
}
