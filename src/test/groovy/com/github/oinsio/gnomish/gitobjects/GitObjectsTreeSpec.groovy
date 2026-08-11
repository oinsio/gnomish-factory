package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25: the tree-editing plumbing behaves like content-addressed git — untouched sibling subtrees
 * keep their hashes, a directory delete drops the whole subtree while leaving history reachable,
 * hooks never fire on the ref update, and the private temp index is always cleaned up.
 */
class GitObjectsTreeSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    def "FR25: an untouched sibling subtree keeps its exact tree hash"() {
        given: 'a base with two subtrees'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}', 'docs/a.md': 'A'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'a commit adds a file only under docs/'
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('docs/b.md', 'B'.bytes)
                ], metadata()))

        then: 'the src subtree entry is byte-identical while docs changed'
        gitOutput(bare, 'ls-tree', tip.hex(), '--', 'src') == gitOutput(bare, 'ls-tree', base.hex(), '--', 'src')
        gitOutput(bare, 'ls-tree', tip.hex(), '--', 'docs') != gitOutput(bare, 'ls-tree', base.hex(), '--', 'docs')
    }

    def "FR25: a directory delete drops the subtree but preserves history"() {
        given: 'a base carrying a .gnomish-task/ subtree'
        def bare = seedBareRepo(tempDir, [
            '.gnomish-task/task.json'          : '{}',
            '.gnomish-task/decisions/s-a1.json': '{}',
            'src/App.java'                     : 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'a commit deletes the whole directory'
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.DeletePath('.gnomish-task')
                ], metadata()))

        then: 'the tip tree has no .gnomish-task but keeps unrelated files'
        def tipTree = gitOutput(bare, 'ls-tree', '-r', tip.hex())
        !tipTree.contains('.gnomish-task')
        tipTree.contains('src/App.java')

        and: 'the earlier commit still holds the directory — history is intact'
        gitOutput(bare, 'ls-tree', '-r', base.hex()).contains('.gnomish-task/task.json')
    }

    def "FR25: hooks never fire on the ref update"() {
        given: 'a bare repo with a reference-transaction hook that would abort every update'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def hooks = bare.resolve('hooks')
        Files.createDirectories(hooks)
        def hook = hooks.resolve('reference-transaction')
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'a commit advances a ref'
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', '{}'.bytes)
                ], metadata()))

        then: 'the hook did not run — the ref moved'
        git.resolveRef('refs/heads/gnomish/t1').get() == tip
    }

    def "FR25: the temporary index is removed after both success and failure"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'a commit succeeds'
        git.commit(new CommitRequest('refs/heads/gnomish/ok', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', '{}'.bytes)
                ], metadata()))

        then: 'no temp index file is left behind'
        leftoverIndexes() == 0

        when: 'a commit fails mid-plumbing (parent object does not exist)'
        git.commit(new CommitRequest('refs/heads/gnomish/bad', Optional.empty(),
                new ObjectId('deadbeefdeadbeefdeadbeefdeadbeefdeadbeef'),
                [
                    new TreeEdit.PutFile('x', 'x'.bytes)
                ], metadata()))

        then: 'the failure surfaces and still leaves no temp index behind'
        thrown(GitObjectsException)
        leftoverIndexes() == 0
    }

    def "FR25: allocateIndex reserves a unique path but deletes the placeholder file it created"() {
        given: 'a CommitBuilder wired directly, bypassing the public commit() facade'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def exec = new GitExec(bare, 'git')
        def index = indexDir(tempDir)
        Files.createDirectories(index)
        def builder = new CommitBuilder(exec, index)
        def method = CommitBuilder.getDeclaredMethod('allocateIndex')
        method.accessible = true

        when: 'the private path-allocation step runs on its own'
        Path allocated = method.invoke(builder) as Path

        then: 'the name is reserved under the index dir but the placeholder file itself is gone'
        allocated.parent == index
        Files.notExists(allocated)
    }

    private long leftoverIndexes() {
        try (var stream = Files.list(indexDir(tempDir))) {
            return stream.filter { it.fileName.toString().startsWith('gnomish-index-') }.collect(Collectors.toList()).size()
        }
    }
}
