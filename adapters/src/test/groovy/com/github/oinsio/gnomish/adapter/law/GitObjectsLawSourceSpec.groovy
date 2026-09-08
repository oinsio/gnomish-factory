package com.github.oinsio.gnomish.adapter.law

import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, D12 of add-base-ref-resolution: what the git-objects realization of {@link LawSource}
 * answers where the two media cannot agree. A git tree has no {@code realpath}, so a symlink
 * entry cannot be canonicalized and is refused fail-closed — read as a law file it would hand
 * back its own target path as content, and followed it would leave the law root unseen. The
 * lexical half of {@code PathSafety} is therefore the whole traversal guard here.
 *
 * <p>The shared semantics — read, regular-file test, listing — are contracted for both
 * realizations by {@link LawSourceContractSpec}.
 */
class GitObjectsLawSourceSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    def "FR11, D12: a symlink entry is refused as a law file"() {
        given:
        def source = lawSource(seedLawTree())

        when:
        def read = source.read('secrets.yaml')

        then:
        source.fileStatus('secrets.yaml') == LawSource.FileStatus.REFUSED
        read instanceof LawSource.Unreadable
        ((LawSource.Unreadable) read).reason().contains('symlink')
    }

    def "FR11: a law root the law commit does not carry holds no law"() {
        given:
        def git = openGitObjects(seedLawTree(), tempDir)
        def source = new GitObjectsLawSource(git, git.resolveRef('refs/heads/base').get(), 'nowhere')

        expect:
        source.fileStatus('') == LawSource.FileStatus.ABSENT
        source.read('config.yaml') instanceof LawSource.Unreadable
        source.list('') == []
    }

    def "FR11: the repository root is not a law root"() {
        given:
        def git = openGitObjects(seedLawTree(), tempDir)

        when:
        new GitObjectsLawSource(git, git.resolveRef('refs/heads/base').get(), ' ')

        then:
        thrown(IllegalArgumentException)
    }

    def "FR11, D12: symlink and gitlink entries are listed as neither file nor directory"() {
        given:
        def source = lawSource(seedLawTree())

        expect:
        LawEntryAssertions.byName(source.list('')) == [
            'config.yaml' : LawEntry.Kind.FILE,
            'stages' : LawEntry.Kind.DIRECTORY,
            'secrets.yaml': LawEntry.Kind.OTHER,
            'vendor' : LawEntry.Kind.OTHER,
        ]
    }

    def "FR11: a gitlink is not a law file"() {
        given:
        def source = lawSource(seedLawTree())

        expect:
        source.fileStatus('vendor') == LawSource.FileStatus.ABSENT
        ((LawSource.Unreadable) source.read('vendor')).reason().contains('not a regular file')
    }

    def "FR11: a law file larger than the read cap is unreadable, never silently truncated"() {
        given: 'a cap smaller than the committed config.yaml'
        def git = openGitObjects(seedLawTree(), tempDir)
        def source = new GitObjectsLawSource(git, git.resolveRef('refs/heads/base').get(), '.gnomish', 2L)

        when:
        def read = source.read('config.yaml')

        then:
        read instanceof LawSource.Unreadable
        ((LawSource.Unreadable) read).reason().contains('config.yaml')
    }

    def "FR11, D12: a reference touching the object store is refused, not attempted"() {
        given:
        def source = lawSource(seedLawTree())

        expect:
        source.read('.git/config') instanceof LawSource.Unreadable
        source.fileStatus('.git/config') == LawSource.FileStatus.ABSENT
        source.list('.git') == []
    }

    def "FR11: the law binds to one commit, never to the ref's later tip"() {
        given: 'the law file was changed after the law commit'
        Path work = initWorkingRepo(tempDir, 'law-work')
        Files.createDirectories(work.resolve('.gnomish'))
        Files.writeString(work.resolve('.gnomish/instructions.md'), 'Original instructions.')
        commitAll(work, 'law')
        String lawCommit = gitOutput(work, 'rev-parse', 'HEAD')
        Files.writeString(work.resolve('.gnomish/instructions.md'), 'Later instructions.')
        commitAll(work, 'later')
        Path bare = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bare.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')

        when:
        def git = openGitObjects(bare, tempDir)
        def source = new GitObjectsLawSource(git, git.resolveRef(lawCommit).get(), '.gnomish')

        then:
        source.read('instructions.md') == new LawSource.Text('Original instructions.')
    }

    private LawSource lawSource(Path bare) {
        GitObjects git = openGitObjects(bare, tempDir)
        new GitObjectsLawSource(git, git.resolveRef('refs/heads/base').get(), '.gnomish')
    }

    /**
     * A bare repo whose {@code refs/heads/base} carries a {@code .gnomish/} tree holding a regular
     * file, a subdirectory, a symlink, and a gitlink — the entry kinds a law reader must tell
     * apart, taken from real {@code ls-tree} output rather than a hand-written string.
     */
    private Path seedLawTree() {
        Path work = initWorkingRepo(tempDir, 'law-work')
        Files.createDirectories(work.resolve('.gnomish/stages'))
        Files.writeString(work.resolve('.gnomish/config.yaml'), 'stages: []')
        Files.writeString(work.resolve('.gnomish/stages/plan.md'), 'plan')
        Files.createSymbolicLink(work.resolve('.gnomish/secrets.yaml'), Path.of('../../etc/passwd'))
        commitAll(work, 'law')
        // A gitlink cannot be produced by a checkout in a temp dir, so it is staged straight into
        // the index: any commit id will do as the submodule's recorded tip.
        gitOutput(work, 'update-index', '--add', '--cacheinfo',
                "160000,${gitOutput(work, 'rev-parse', 'HEAD')},.gnomish/vendor")
        gitOutput(work, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'vendor')
        Path bare = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bare.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        bare
    }
}
