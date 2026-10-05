package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The mutation scope has one owner, {@code MutationScopeSource} in {@code build-logic} (FR5 of
 * scope-pit-locally, design D1): CI workflows pass a mode, never a list, and compute no diff of
 * their own (M2). Before that change {@code ci.yml} resolved a merge base and turned a
 * {@code git diff} into a {@code -PpitScope=} class list — the second half of a rule the build
 * also implemented. This gate keeps that half from growing back: no workflow under
 * {@code .github/workflows/} runs {@code git merge-base} or {@code git diff}, and no workflow
 * passes a {@code pitScope} value other than {@code all}, whether as {@code -P} or as an
 * {@code ORG_GRADLE_PROJECT_} variable. Comment lines are not judged: a workflow may explain the
 * owner it defers to.
 *
 * <p>It also pins the tokens of the two gate workflows (NFR-S1, design D7): the nightly carries
 * exactly {@code contents: read} and {@code issues: write}, {@code ci.yml} {@code contents: read}
 * only, and neither lets a job declare a wider block of its own.
 */
class CiScopeOwnerSpec extends Specification {

    /** The workflows that run the gate; the scan must reach both, or it judged nothing. */
    private static final List<String> GATE_WORKFLOWS = [
        'ci.yml',
        'pitest-nightly.yml'
    ]

    private static final List<Pattern> BANNED = [
        ~/\bgit\s+merge-base\b/,
        ~/\bgit\s+diff\b/,
        // a pitScope value other than the whole-tree mode `all`, in any spelling Gradle reads
        ~/pitScope\s*[=:]\s*["']?(?!all(?:["'\s]|$))/
    ]

    @TempDir
    Path scratch

    def "M2: no workflow computes a diff or passes a mutation scope other than all"() {
        given:
        def workflows = RepoSourceTree.repoRoot().resolve('.github/workflows')

        expect: 'the scan reached the workflows that run the gate'
        scanned(workflows).containsAll(GATE_WORKFLOWS)

        and:
        violations(workflows).isEmpty()
    }

    def "M2: a planted '#planted' in a copy of ci.yml is reported"() {
        given:
        def workflows = copyOfCi("      - run: ${planted}")

        expect:
        violations(workflows) == [
            "ci.yml: - run: ${planted}".toString()
        ]

        where:
        planted << [
            './gradlew check -PpitScope=com.github.oinsio.gnomish.*',
            './gradlew check "-PpitScope=${{ steps.changed-classes.outputs.classes }}"',
            './gradlew check -PpitScope=',
            './gradlew check -PpitScope=all,com.github.oinsio.gnomish.Foo',
            'echo "ORG_GRADLE_PROJECT_pitScope: com.github.oinsio.gnomish.Foo"',
            'git merge-base origin/main HEAD',
            'git diff --name-only "$base...HEAD"'
        ]
    }

    def "M2: the whole-tree mode and a comment naming the old way are not reported"() {
        given:
        def workflows = copyOfCi(planted)

        expect:
        violations(workflows).isEmpty()

        where:
        planted << [
            '      - run: ./gradlew check -PpitScope=all',
            '      - run: ./gradlew check "-PpitScope=all"',
            '      # the build, not git merge-base or git diff here, owns the scope'
        ]
    }

    def "NFR-S1: the nightly grants exactly contents read and issues write, and ci.yml contents read only"() {
        given:
        def workflows = RepoSourceTree.repoRoot().resolve('.github/workflows')

        expect:
        permissions(workflows.resolve('pitest-nightly.yml')) == [
            'contents: read',
            'issues: write'
        ]
        permissions(workflows.resolve('ci.yml')) == ['contents: read']
    }

    def "NFR-S1: a widened '#planted' in a copy of #file is reported"() {
        given: 'a copy whose top-level block gains one grant, or a job that declares its own'
        def original = Files.readString(RepoSourceTree.repoRoot().resolve(".github/workflows/${file}"))
        def copy = scratch.resolve(file)
        Files.writeString(copy, original.replaceFirst(/(?m)^permissions:\n/, "permissions:\n${planted}\n"))

        expect:
        permissions(copy) != expected

        where:
        file | planted | expected
        'pitest-nightly.yml' | '  pull-requests: write' | [
            'contents: read',
            'issues: write'
        ]
        'pitest-nightly.yml' | '  actions: read' | [
            'contents: read',
            'issues: write'
        ]
        'ci.yml' | '  issues: write' | ['contents: read']
    }

    def "NFR-S1: a job-level permissions block in a copy of the nightly is reported"() {
        given:
        def original = Files.readString(RepoSourceTree.repoRoot().resolve('.github/workflows/pitest-nightly.yml'))
        def copy = scratch.resolve('pitest-nightly.yml')
        Files.writeString(copy, original.replaceFirst(/(?m)^ {4}runs-on:/, '    permissions:\n      actions: read\n    runs-on:'))

        expect:
        permissions(copy) == [
            'contents: read',
            'issues: write',
            'job: permissions'
        ]
    }

    /**
     * The grants a workflow's token carries, as {@code key: value} lines of the top-level
     * {@code permissions:} block, comments stripped; any indented {@code permissions:} key — a
     * job widening its own token — is listed as {@code job: permissions}, so it cannot pass.
     */
    private static List<String> permissions(Path workflow) {
        def lines = Files.readAllLines(workflow).collect {
            it.replaceFirst(/\s+#.*$/, '')
        }
        def start = lines.indexOf('permissions:')
        def grants = start < 0 ? [] : lines.drop(start + 1)
        .takeWhile { it.startsWith(' ') || it.isEmpty() }
        .findAll { !it.trim().isEmpty() && !it.trim().startsWith('#') }
        .collect { it.trim() }
        grants + lines.findAll {
            it ==~ /\s+permissions:.*/
        }.collect {
            'job: permissions'
        }
    }

    /** A scratch workflows directory holding the real ci.yml with one line appended. */
    private Path copyOfCi(String line) {
        def ci = RepoSourceTree.repoRoot().resolve('.github/workflows/ci.yml')
        Files.writeString(scratch.resolve('ci.yml'), Files.readString(ci) + line + '\n')
        scratch
    }

    private static List<String> scanned(Path workflows) {
        workflowFiles(workflows).collect { it.fileName.toString() }
    }

    /** Every non-comment line matching a banned pattern, as {@code file: trimmed line}. */
    private static List<String> violations(Path workflows) {
        workflowFiles(workflows).collectMany { file ->
            Files.readAllLines(file)
            .collect { it.trim() }
            .findAll { line ->
                !line.startsWith('#') && BANNED.any {
                    line =~ it
                }
            }
            .collect { "${file.fileName}: ${it}".toString() }
        }
    }

    private static List<Path> workflowFiles(Path workflows) {
        Files.list(workflows).withCloseable { files ->
            files.filter {
                it.fileName.toString() ==~ /.*\.ya?ml/
            }.sorted().toList()
        }
    }
}
