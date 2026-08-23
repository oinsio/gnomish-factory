package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of fix-lifecycle-push (design D2), FR8 of add-serve-sandbox-lifecycle (design D5): the one
 * reader of {@code remote get-url origin}, answering both the push precondition ("configured at
 * all?") and the project-identity input ("what URL?"), degrading to false/empty rather than
 * throwing for a clone with no {@code origin} at all.
 */
class OriginRemoteSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def origin = new OriginRemote(runner)

    def "reads the configured origin remote URL"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(repo, 'origin', bareRepo.toString())

        expect:
        origin.url(repo) == Optional.of(bareRepo.toString())
    }

    def "a clone with no origin remote configured reads as empty, not a thrown exception"() {
        given:
        def repo = initWorkingRepo(tempDir)

        expect:
        origin.url(repo) == Optional.empty()
    }

    def "a configured origin satisfies the push precondition"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(repo, 'origin', bareRepo.toString())

        expect:
        origin.isConfigured(repo)
    }

    // The two answers come apart here: `remote get-url` SUCCEEDS but prints nothing. The URL reader
    // must report empty (there is no identity to digest, FR8 of add-serve-sandbox-lifecycle) while
    // the push precondition still says configured — a remote exists, the push is git's to refuse.
    // Real git cannot be made to print a blank URL for a configured remote, so the runner's own
    // git-binary seam stands in for it.
    def "a configured origin whose URL reads blank is empty to the URL reader, still configured to the precondition"() {
        given:
        def blankUrlGit = tempDir.resolve('blank-url-git')
        blankUrlGit.toFile().text = '#!/bin/sh\necho ""\n'
        blankUrlGit.toFile().executable = true
        def blankOrigin = new OriginRemote(new GitProcessRunner(blankUrlGit.toString()))

        expect:
        blankOrigin.url(tempDir) == Optional.empty()
        blankOrigin.isConfigured(tempDir)
    }

    def "a clone with no origin remote fails the push precondition"() {
        given:
        def repo = initWorkingRepo(tempDir)

        expect:
        !origin.isConfigured(repo)
    }
}
