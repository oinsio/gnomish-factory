package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11 of add-base-ref-resolution: listing a tree out of bare objects, so law read by ref can
 * enumerate a directory without a checkout. Regular files, directories, and symlink entries are
 * reported as distinct kinds — the reader that must refuse a symlinked law file (design D12) can
 * only do that if the listing keeps them apart — and a path that is not a tree in the commit
 * (absent, or a blob) is refused rather than reported as an empty directory.
 */
class GitObjectsListTreeSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    def "FR11: regular files, directories, and symlinks are listed as distinct kinds"() {
        given: 'a committed .gnomish/ tree holding one of each entry kind'
        def git = openGitObjects(seedLawTree(), tempDir)
        def law = git.resolveRef('refs/heads/base').get()

        when:
        def kindsByName = git.listTree(law, '.gnomish').collectEntries {
            [it.name(), it.kind()]
        }

        then: 'each entry carries its own kind — a symlink is never a file'
        kindsByName == [
            'config.yaml' : TreeEntry.Kind.FILE,
            'run.sh' : TreeEntry.Kind.FILE,
            'stages' : TreeEntry.Kind.DIRECTORY,
            'secrets.yaml': TreeEntry.Kind.SYMLINK,
            'vendor' : TreeEntry.Kind.OTHER,
        ]
    }

    def "FR11: an entry carries its own name, not the path it was listed under"() {
        given:
        def git = openGitObjects(seedLawTree(), tempDir)
        def law = git.resolveRef('refs/heads/base').get()

        expect: 'the nested directory lists its own children by bare name'
        git.listTree(law, '.gnomish/stages') == [
            new TreeEntry('plan.md', TreeEntry.Kind.FILE)
        ]
    }

    def "FR11: an absent path is refused, never reported as an empty directory"() {
        given:
        def git = openGitObjects(seedLawTree(), tempDir)
        def law = git.resolveRef('refs/heads/base').get()

        when:
        git.listTree(law, '.gnomish/nowhere')

        then:
        def failure = thrown(MissingObjectException)
        failure.message.contains('.gnomish/nowhere')
        failure.message.contains(law.hex())
    }

    def "FR11: a blob-vs-tree mismatch is refused"() {
        given:
        def git = openGitObjects(seedLawTree(), tempDir)
        def law = git.resolveRef('refs/heads/base').get()

        when: 'a file is asked to list itself'
        git.listTree(law, '.gnomish/config.yaml')

        then:
        def failure = thrown(MissingObjectException)
        failure.message.contains('.gnomish/config.yaml')
    }

    def "FR11: listing validates the path before it reaches git (#path)"() {
        given:
        def git = openGitObjects(seedLawTree(), tempDir)
        def law = git.resolveRef('refs/heads/base').get()

        when:
        git.listTree(law, path)

        then:
        thrown(InvalidTreePathException)

        where:
        path << [
            '/etc',
            '../outside',
            '.git',
            ''
        ]
    }

    /**
     * A bare repo whose {@code refs/heads/base} carries a {@code .gnomish/} tree with a regular
     * file, an executable file, a subdirectory, a symlink, and a gitlink — the five modes git can
     * put in a tree, so the kind mapping is exercised against real {@code ls-tree} output rather
     * than a hand-written string.
     */
    private Path seedLawTree() {
        Path work = initWorkingRepo(tempDir, 'law-work')
        Files.createDirectories(work.resolve('.gnomish/stages'))
        Files.writeString(work.resolve('.gnomish/config.yaml'), 'stages: []')
        Files.writeString(work.resolve('.gnomish/stages/plan.md'), 'plan')
        Path script = work.resolve('.gnomish/run.sh')
        Files.writeString(script, '#!/bin/sh\n')
        script.toFile().setExecutable(true)
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
