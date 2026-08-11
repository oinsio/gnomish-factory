package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Test fixture for the {@code gitobjects} specs: seeds a real <em>bare</em> repository with a base
 * commit (built through a throwaway working clone), opens {@link GitObjects} against it, and mints
 * deterministic {@link CommitMetadata}. Reuses {@link BareGitRepoFixture} for the raw git plumbing.
 *
 * <p>Supports FR25 of add-sandbox-core.
 */
trait GitObjectsFixture implements BareGitRepoFixture {

    /**
     * Seeds a bare repo whose {@code refs/heads/base} points at a commit containing {@code files}
     * (relative path → text content). Returns the bare git dir — which has no working tree, so any
     * later checkout would be visible.
     */
    Path seedBareRepo(Path tempDir, Map<String, String> files) {
        Path work = initWorkingRepo(tempDir, 'seed-work')
        files.each { rel, content ->
            Path target = work.resolve(rel)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        commitAll(work, 'base')
        Path bare = initBareRepo(tempDir, 'origin.git')
        addRemote(work, 'origin', bare.toString())
        gitOutput(work, 'push', 'origin', 'HEAD:refs/heads/base')
        bare
    }

    GitObjects openGitObjects(Path bareGitDir, Path tempDir) {
        Path index = tempDir.resolve('index')
        Files.createDirectories(index)
        GitObjects.open(bareGitDir, index)
    }

    Path indexDir(Path tempDir) {
        tempDir.resolve('index')
    }

    CommitMetadata metadata(long epoch = 1_700_000_000L, String message = 'gnomish: test') {
        def who = new CommitIdentity('gnome', 'gnome@factory')
        new CommitMetadata(who, Instant.ofEpochSecond(epoch), who, Instant.ofEpochSecond(epoch), message)
    }
}
