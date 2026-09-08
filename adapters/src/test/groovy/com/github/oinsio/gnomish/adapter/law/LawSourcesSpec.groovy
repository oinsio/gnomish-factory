package com.github.oinsio.gnomish.adapter.law

import com.github.oinsio.gnomish.app.LawBinding
import com.github.oinsio.gnomish.app.UsageException
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, M5, D12 of add-base-ref-resolution: the seam that turns the use-case layer's
 * {@link LawBinding} into an opened {@link LawSource}. Two things are pinned here and nowhere
 * else — which realization each shape opens, and that the revision is peeled exactly once into a
 * typed commit id, so the frozen law and the external-check pin guard name the same commit however
 * the repository's refs move afterwards.
 */
class LawSourcesSpec extends Specification implements GitObjectsFixture {

    private static final Map<String, String> TREE = [
        '.gnomish/instructions.md': 'Committed law.',
    ]

    @TempDir
    Path tempDir

    // FR11: a working-tree binding opens the working-tree realization at the binding's own law
    //     root, and reads a file that was never committed — the manual-run edit loop.
    def "FR11: a working-tree binding reads uncommitted law from the repository's .gnomish directory"() {
        given:
        Path root = tempDir.resolve('clone')
        Files.createDirectories(root.resolve('.gnomish'))
        Files.writeString(root.resolve('.gnomish/instructions.md'), 'Uncommitted law.')
        def git = openGitObjects(checkedOutAtBase(seedBareRepo(tempDir, TREE)), tempDir)

        when:
        def bound = LawSources.open(LawBinding.workingTree(root), git)

        then:
        bound.source() instanceof WorkingTreeLawSource
        bound.source().read('instructions.md') == new LawSource.Text('Uncommitted law.')

        and: 'with no law commit resolved, the pin guard compares against the checkout, peeled once'
        bound.lawCommit() == git.resolveRef(GitObjects.HEAD).get()
    }

    // FR11, D12: an in-place workspace need not be a repository at all; the working tree is still
    //     law there, and the pin is simply absent — the guard fails a pinned check closed later.
    def "FR11: a working-tree binding over no repository reads its law and carries no pin"() {
        given:
        Path root = tempDir.resolve('workspace')
        Files.createDirectories(root.resolve('.gnomish'))
        Files.writeString(root.resolve('.gnomish/instructions.md'), 'Law without git.')
        def git = GitObjects.open(root.resolve('.git'), tempDir)

        when:
        def bound = LawSources.open(LawBinding.workingTree(root), git)

        then:
        bound.source().read('instructions.md') == new LawSource.Text('Law without git.')
        bound.lawCommit() == null
    }

    // FR11, M5: a revision binding reads out of git objects — the working tree plays no part — and
    //     hands back the very commit it read, so law and pin cannot drift apart.
    def "FR11, M5: a revision binding reads committed law and pins the same commit"() {
        given: 'a bare repo whose law is committed, and a working tree saying something else'
        Path bare = seedBareRepo(tempDir, TREE)
        def git = openGitObjects(bare, tempDir)
        def sha = git.resolveRef('refs/heads/base').get()

        when:
        def bound = LawSources.open(LawBinding.atRevision(tempDir, 'refs/heads/base'), git)

        then:
        bound.source() instanceof GitObjectsLawSource
        bound.source().read('instructions.md') == new LawSource.Text('Committed law.')

        and: 'the pin is the peeled commit itself, not the ref name it was asked for'
        bound.lawCommit() == sha
    }

    // M5, D12: the revision is peeled once, at binding. Moving the clone's HEAD afterwards moves
    //     neither the law nor the pin — the double-resolve incident the typed id exists to prevent.
    def "M5, D12: moving the clone's HEAD after binding changes neither the law nor the pin"() {
        given: 'a clone checked out at the law commit'
        def git = openGitObjects(checkedOutAtBase(seedBareRepo(tempDir, TREE)), tempDir)
        def lawCommit = git.resolveRef(GitObjects.HEAD).get()
        def bound = LawSources.open(LawBinding.atCheckout(tempDir), git)

        when: 'HEAD moves on to a commit with different law'
        git.commit(new CommitRequest('refs/heads/base', Optional.of(lawCommit), lawCommit, [
            new TreeEdit.PutFile('.gnomish/instructions.md', 'Later law.'.getBytes(StandardCharsets.UTF_8))
        ], metadata()))
        assert git.resolveRef(GitObjects.HEAD).get() != lawCommit

        then: 'the bound law is still the commit that was peeled, and so is the pin'
        bound.source().read('instructions.md') == new LawSource.Text('Committed law.')
        bound.lawCommit() == lawCommit
    }

    // FR11: a revision nothing resolves is an operator-visible refusal, never a silent fall back
    //     to the clone's checkout — that fall back is exactly what would hide an obsolete base.
    def "FR11: a revision that resolves to no commit refuses instead of falling back"() {
        given:
        def git = openGitObjects(seedBareRepo(tempDir, TREE), tempDir)

        when:
        LawSources.open(LawBinding.atRevision(tempDir, 'refs/heads/gone'), git)

        then:
        def e = thrown(UsageException)
        e.message.contains('refs/heads/gone')
        e.message.contains('cannot bind the pipeline law')
    }

    /** Points the bare repository's {@code HEAD} at {@code refs/heads/base}, as a clone's checkout would. */
    private Path checkedOutAtBase(Path bare) {
        gitOutput(bare, 'symbolic-ref', 'HEAD', 'refs/heads/base')
        bare
    }
}
