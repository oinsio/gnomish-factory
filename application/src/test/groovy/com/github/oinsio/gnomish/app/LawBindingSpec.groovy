package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR11, D12 of add-base-ref-resolution: {@link LawBinding} is the one owner of the law-root rule
 * and of "law belongs to this repository", so this pins what each shape says about where law
 * lives — the questions no call site is allowed to answer for itself.
 */
class LawBindingSpec extends Specification {

    private static final Path REPO = Path.of('/projects/widgets')

    // FR11: the working-tree binding resolves the law root itself, so no caller ever spells
    //     `.gnomish` — the load-vs-run divergence D12 closes started with exactly that duplication.
    def "FR11: a working-tree binding roots law at the repository's .gnomish directory"() {
        when:
        def binding = LawBinding.workingTree(REPO)

        then:
        binding instanceof LawBinding.WorkingTree
        (binding as LawBinding.WorkingTree).lawRoot() == REPO.resolve('.gnomish')
    }

    // FR11: a git-objects binding names a revision and nothing else — peeling it to a commit needs
    //     git, which lives an adapter down.
    def "FR11: a revision binding carries the revision verbatim"() {
        when:
        def binding = LawBinding.atRevision(REPO, 'release/1.18')

        then:
        binding == new LawBinding.AtRevision(REPO, 'release/1.18')
    }

    // FR11: "this path resolved no base of its own yet" is spelled once, here, rather than as a
    //     "HEAD" literal repeated across the take, serve, container and resume paths.
    def "FR11: the checkout binding is the clone's HEAD, named rather than spelled"() {
        expect:
        LawBinding.atCheckout(REPO) == new LawBinding.AtRevision(REPO, GitObjects.HEAD)
    }

    // D12: every shape knows the repository its law belongs to — the root the pin guard reads its
    //     repository-relative pin paths from — so law and pin cannot be read from two repositories
    //     by a transposed argument.
    def "D12: every binding shape names the repository its law belongs to (#shape)"() {
        expect:
        binding.repositoryRoot() == REPO

        where:
        shape | binding
        'working tree' | LawBinding.workingTree(REPO)
        'revision' | LawBinding.atRevision(REPO, 'v1.2.3')
        'checkout' | LawBinding.atCheckout(REPO)
    }

    // D12: the law root is the same directory name in both media.
    def "D12: the law root is .gnomish"() {
        expect:
        LawBinding.LAW_ROOT == '.gnomish'
    }
}
