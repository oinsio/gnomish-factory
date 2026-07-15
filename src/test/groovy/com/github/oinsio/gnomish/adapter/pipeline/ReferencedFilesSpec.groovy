package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition.Executor
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.IgnoreIf
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
 * Path traversal (../ escaping the root) is task 6.4: ReferencedFiles delegates the
 * resolve-within-root decision to PathSafety (NFR-S2). Traversal is checked first — an
 * escaping reference is reported as a traversal error and its existence is never checked,
 * so a single reference never yields both a traversal and a "does not exist" error.
 * Implements FR6 and NFR-S2 of load-pipeline-config.
 */
class ReferencedFilesSpec extends Specification {

    @TempDir
    Path root

    private void write(String relative, String text) {
        Path target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
    }

    private static StageDefinition stage(
            String name, String instructionsRef, List<VerifyCheck> verify = []) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new Executor(ExecutorType.API, 'model', [:]),
                instructionsRef,
                verify,
                new AutonomyLimits(1),
                AdvancementMode.AUTO)
    }

    private static VerifyCheck.Judge judge(String criteriaFile) {
        new VerifyCheck.Judge(criteriaFile, 'model', [:], 1)
    }

    private static VerifyCheck.Command command() {
        new VerifyCheck.Command('true')
    }

    private static ConfigError missingInstructions(String stageName, String ref) {
        new ConfigError(
                "stages/${stageName}/stage.yaml".toString(),
                'instructions',
                "referenced instructions file '${ref}' does not exist".toString())
    }

    private static ConfigError missingCriteria(String stageName, int index, String ref) {
        new ConfigError(
                "stages/${stageName}/stage.yaml".toString(),
                "verify[${index}].criteriaFile".toString(),
                "referenced acceptance-criteria file '${ref}' does not exist".toString())
    }

    private static ConfigError escapingInstructions(String stageName, String ref) {
        new ConfigError(
                "stages/${stageName}/stage.yaml".toString(),
                'instructions',
                "referenced instructions file '${ref}' escapes the configuration root".toString())
    }

    private static ConfigError escapingCriteria(String stageName, int index, String ref) {
        new ConfigError(
                "stages/${stageName}/stage.yaml".toString(),
                "verify[${index}].criteriaFile".toString(),
                "referenced acceptance-criteria file '${ref}' escapes the configuration root".toString())
    }

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
        scenario                      | built                                                                          || expected
        'missing instructions'        | stage('plan', 'stages/plan/absent.md')                                    || missingInstructions('plan', 'stages/plan/absent.md')
        'instructions is a directory' | stage('plan', 'stages/plan/as-dir.md')                                    || missingInstructions('plan', 'stages/plan/as-dir.md')
        'missing judge criteria'      | stage('plan', 'stages/plan/instructions.md', [
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
                new VerifyCheck.External('ci', Duration.ofSeconds(5), Duration.ofSeconds(60))
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
        def before = snapshot(root)

        when:
        ReferencedFiles.check(root, stages)

        then: 'nothing on disk changed (NFR-R1: read-only)'
        snapshot(root) == before
    }

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

    private static Map<String, String> snapshot(Path root) {
        Map<String, String> files = [:]
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { p ->
                files.put(root.relativize(p).toString(), Files.readString(p))
            }
        }
        files
    }
}
