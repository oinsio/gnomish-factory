package com.github.oinsio.gnomish.gitobjects

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of own-git-invocation-policy: a {@code refs/replace/} entry planted in the repository does not
 * change what {@link GitObjects} reads — the git-object library runs git under the invocation
 * policy's {@code core.useReplaceRefs=false} (design D1, D2), so the original object comes back.
 */
class GitObjectsReplaceRefSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    def "FR1: a planted replace ref does not substitute the commit a read names"() {
        given: 'a base commit and a second commit with other content under the same path'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def substitute = git.commit(new CommitRequest('refs/heads/substitute', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('src/App.java', 'class Forged {}'.bytes)
                ], metadata()))

        and: 'a replace ref substituting the second commit for the base'
        plantReplacement(bare, base.hex(), substitute.hex())

        expect: 'the read of the base sees the original commit, its message and its content'
        new String(git.readBlob(base, 'src/App.java', 1024), StandardCharsets.UTF_8) == 'class App {}'
        git.commitMessage(base).get().trim() == 'base'
        !git.historyContains(base, 'gnomish: test')
    }
}
