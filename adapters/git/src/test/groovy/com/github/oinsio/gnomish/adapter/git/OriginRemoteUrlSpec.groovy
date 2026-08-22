package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8 of add-serve-sandbox-lifecycle (design D5): reads the {@code origin} remote URL a clone is
 * configured with — the input the sandbox's project-identity digest is derived from — and
 * degrades to empty, never a thrown exception, for a bare-local clone with no {@code origin} at
 * all.
 */
class OriginRemoteUrlSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    def "reads the configured origin remote URL"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def bareRepo = initBareRepo(tempDir, 'origin.git')
        addRemote(repo, 'origin', bareRepo.toString())

        expect:
        OriginRemoteUrl.read(runner, repo) == Optional.of(bareRepo.toString())
    }

    def "a clone with no origin remote configured reads as empty, not a thrown exception"() {
        given:
        def repo = initWorkingRepo(tempDir)

        expect:
        OriginRemoteUrl.read(runner, repo) == Optional.empty()
    }
}
