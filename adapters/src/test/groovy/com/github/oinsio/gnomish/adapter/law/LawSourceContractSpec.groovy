package com.github.oinsio.gnomish.adapter.law

import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11, D12 of add-base-ref-resolution: the port-level contract of {@link LawSource}, run
 * against <em>both</em> realizations over identical trees — the same {@code .gnomish/} tree
 * written to disk and committed into a bare repository — so "read a file, test a regular file,
 * list a directory, all relative to a root" means one thing whether the law comes from the
 * factory clone's working tree or from git objects at the law commit.
 *
 * <p>Symlink entries are part of the contract, not an each-to-its-own difference (revised by D12):
 * both realizations refuse one under the law root without following it, so the same commit is
 * either law in every medium or law in none.
 */
class LawSourceContractSpec extends Specification implements GitObjectsFixture {

    private static final Map<String, String> TREE = [
        '.gnomish/config.yaml' : 'stages: []\n',
        '.gnomish/pipeline.yaml' : 'stages: [plan]\n',
        '.gnomish/stages/plan/stage.yaml' : 'instructions: stages/plan/instructions.md\n',
        '.gnomish/stages/plan/instructions.md' : 'Plan the work.',
        '.gnomish/stages/review/stage.yaml' : 'instructions: stages/review/instructions.md\n',
        'outside.md' : 'Not law.',
    ]

    /**
     * Symlink entries of the same tree: a file link whose target stays under the law root, one that
     * leaves it, and a directory link standing in for a real stage directory.
     */
    private static final Map<String, String> LINKS = [
        '.gnomish/alias.md' : 'stages/plan/instructions.md',
        '.gnomish/escape.md': '../outside.md',
        '.gnomish/linked' : 'stages',
    ]

    @TempDir
    Path tempDir

    def "FR11: a law file is read by its root-relative path (#kind)"() {
        given:
        def source = source(kind)

        expect:
        source.read('config.yaml') == new LawSource.Text('stages: []\n')
        source.read('stages/plan/instructions.md') == new LawSource.Text('Plan the work.')

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11: an absent law file is unreadable, never an exception (#kind)"() {
        given:
        def source = source(kind)

        expect:
        source.read('stages/review/instructions.md') instanceof LawSource.Unreadable

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11: the root itself is not a law file (#kind)"() {
        given:
        def source = source(kind)

        expect:
        source.read('') instanceof LawSource.Unreadable
        source.fileStatus('') == LawSource.FileStatus.ABSENT

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11: a regular file, an absent path and a directory are told apart (#kind)"() {
        given:
        def source = source(kind)

        expect:
        source.fileStatus('stages/plan/instructions.md') == LawSource.FileStatus.REGULAR_FILE
        source.fileStatus('stages/review/instructions.md') == LawSource.FileStatus.ABSENT
        source.fileStatus('stages') == LawSource.FileStatus.ABSENT

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11, D12: a path escaping the root is refused before anything outside is touched (#kind, #ref)"() {
        given:
        def source = source(kind)

        expect:
        source.fileStatus(ref) == LawSource.FileStatus.REFUSED
        source.read(ref) instanceof LawSource.Unreadable
        source.list(ref) == []

        where:
        [kind, ref] << [
            ['working tree', 'git objects'],
            [
                '../outside.md',
                'stages/../../outside.md',
                '/etc/passwd'
            ]
        ].combinations()
    }

    def "FR11, D12: a symlink entry under the law root is refused in every medium (#kind, #ref)"() {
        given:
        def source = source(kind)

        expect: 'the same verdict from both realizations, whether or not the target stays under the root'
        source.fileStatus(ref) == LawSource.FileStatus.REFUSED
        source.read(ref) instanceof LawSource.Unreadable
        !(source.read(ref).reason().contains('Plan the work.'))

        where:
        [kind, ref] << [
            ['working tree', 'git objects'],
            ['alias.md', 'escape.md']
        ].combinations()
    }

    // D12 (revised after the 5.4 review): a symlinked directory is REFUSED at the linked segment in
    //     both media — not "absent" in git objects, where plain ls-tree cannot see through a 120000
    //     entry and would report a missing tree, indistinguishable from a deleted directory.
    def "FR11, D12: a symlinked directory is refused at the linked segment in every medium (#kind)"() {
        given:
        def source = source(kind)

        expect: 'nothing beneath the link is law, and the verdict is a refusal, not an absence'
        source.fileStatus('linked/plan/instructions.md') == LawSource.FileStatus.REFUSED
        source.read('linked/plan/instructions.md') instanceof LawSource.Unreadable
        !(source.read('linked/plan/instructions.md').reason().contains('Plan the work.'))
        source.list('linked') == []
        source.list('linked/plan') == []

        and: 'the same files reached by their real path are ordinary law'
        source.fileStatus('stages/plan/instructions.md') == LawSource.FileStatus.REGULAR_FILE

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11, D12: a symlink entry is listed as neither file nor directory (#kind)"() {
        given:
        def source = source(kind)

        expect:
        LawEntryAssertions.byName(source.list(''))['alias.md'] == LawEntry.Kind.OTHER
        LawEntryAssertions.byName(source.list(''))['linked'] == LawEntry.Kind.OTHER

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11: a directory lists its own entries by bare name and kind (#kind)"() {
        given:
        def source = source(kind)

        expect:
        LawEntryAssertions.byName(source.list('')) == [
            'alias.md' : LawEntry.Kind.OTHER,
            'config.yaml' : LawEntry.Kind.FILE,
            'escape.md' : LawEntry.Kind.OTHER,
            'linked' : LawEntry.Kind.OTHER,
            'pipeline.yaml': LawEntry.Kind.FILE,
            'stages' : LawEntry.Kind.DIRECTORY,
        ]
        LawEntryAssertions.byName(source.list('stages')) == [
            'plan' : LawEntry.Kind.DIRECTORY,
            'review': LawEntry.Kind.DIRECTORY,
        ]
        LawEntryAssertions.byName(source.list('stages/plan')) == [
            'instructions.md': LawEntry.Kind.FILE,
            'stage.yaml' : LawEntry.Kind.FILE,
        ]

        where:
        kind << ['working tree', 'git objects']
    }

    def "FR11: listing an absent path or a regular file is empty, never a fault (#kind)"() {
        given:
        def source = source(kind)

        expect:
        source.list('stages/nowhere') == []
        source.list('config.yaml') == []
        source.list('config.yaml/beneath') == []

        where:
        kind << ['working tree', 'git objects']
    }

    /** Builds the requested realization over the shared tree — on disk, or committed into a bare repo. */
    private LawSource source(String kind) {
        kind == 'working tree' ? workingTreeSource() : gitObjectsSource()
    }

    private LawSource workingTreeSource() {
        Path root = tempDir.resolve('clone')
        TREE.each { rel, content ->
            Path target = root.resolve(rel)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        LINKS.each { rel, target ->
            Files.createSymbolicLink(root.resolve(rel), Path.of(target))
        }
        new WorkingTreeLawSource(root.resolve('.gnomish'))
    }

    private LawSource gitObjectsSource() {
        Path bare = seedBareRepo(tempDir, TREE, LINKS)
        def git = openGitObjects(bare, tempDir)
        new GitObjectsLawSource(git, git.resolveRef('refs/heads/base').get(), '.gnomish')
    }
}
