package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.gitobjects.ObjectId
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR8, FR11, FR15, D12 of add-base-ref-resolution: the one rule that decides a manual {@code
 * gnomish run}'s law source — a {@code --base} sends it to that ref's own commit, its absence keeps
 * the clone's working tree so a pipeline author's uncommitted edit still runs — and the one peel
 * that makes that law commit the task branch's start point too.
 */
class ManualRunLawBindingSpec extends Specification {

    private static final Path CLONE = Path.of('/clones/widgets')

    private static final ObjectId LAW_COMMIT = ObjectId.of('0123456789abcdef0123456789abcdef01234567')

    // FR8, UX3: without --base nothing was resolved, so the working tree stays the law — the one
    //     behaviour this change promises not to alter.
    def "FR8: a manual run without --base keeps the clone's working tree as its law"() {
        given:
        def assembly = Stub(RunAssembly) {
            lawCommitOf(_) >> LAW_COMMIT
        }

        expect:
        ManualRunLawBinding.bind(assembly, CLONE, null).binding() == new LawBinding.WorkingTree(CLONE)
    }

    // FR11: with --base a ref was resolved, so the law comes from that ref's own commit — by fact,
    //     not by mode, exactly as take and serve bind theirs.
    def "FR11: a manual run with --base binds law at that ref"() {
        given:
        def assembly = Stub(RunAssembly) {
            lawCommitOf(_) >> LAW_COMMIT
        }

        expect:
        ManualRunLawBinding.bind(assembly, CLONE, 'v1.2.3').binding() == new LawBinding.AtRevision(CLONE, 'v1.2.3')
    }

    // FR15, D12 (revised 2026-09-10): the branch's start point is the very commit the law was
    //     peeled at, so the manual tier hands the repository port a commit and never a name.
    def "FR15: the bound law commit is what the task branch starts from"() {
        given:
        def assembly = Mock(RunAssembly)

        when:
        def bound = ManualRunLawBinding.bind(assembly, CLONE, 'v1.2.3')

        then:
        1 * assembly.lawCommitOf(new LawBinding.AtRevision(CLONE, 'v1.2.3')) >> LAW_COMMIT
        bound.lawCommit() == LAW_COMMIT
    }

    // FR15, UX3: a --dir that is no repository resolves no checkout, so there is no commit to
    //     start a task branch from. Refusing names the missing repository; falling back to a ref
    //     name is exactly what the peel exists to prevent.
    def "FR15: a workspace that is no repository refuses instead of falling back to a name"() {
        given:
        def assembly = Stub(RunAssembly) {
            lawCommitOf(_) >> null
        }

        when:
        ManualRunLawBinding.bind(assembly, CLONE, null)

        then:
        def e = thrown(UsageException)
        e.message.contains(CLONE.toString())
        e.message.contains('not a git repository')
    }
}
