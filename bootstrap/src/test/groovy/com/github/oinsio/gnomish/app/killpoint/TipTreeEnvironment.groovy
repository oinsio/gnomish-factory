package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.jspecify.annotations.Nullable

/**
 * A box whose working copy is the task branch's tree at the moment it was materialized, read as
 * bare objects from the container medium's repository — what a round's box cloned from the tip
 * sees, without a daemon. Only the file channel's read is real: the kill-point rows drive a round's
 * decision read through it, and nothing else a box does.
 */
class TipTreeEnvironment implements TaskExecutionEnvironment, BareGitRepoFixture {

    private final Path repoDir
    private String commit

    TipTreeEnvironment(Path repoDir) {
        this.repoDir = repoDir
    }

    @Override
    void materialize(String branch, @Nullable String commitPin) {
        commit = commitPin ?: gitOutput(repoDir, 'rev-parse', "refs/heads/${branch}")
    }

    @Override
    Optional<byte[]> readFile(String path, long sizeCap) {
        String blob = "${commit}:${path}"
        gitExitCode(repoDir, 'cat-file', '-e', blob) == 0
                ? Optional.of(gitOutput(repoDir, 'show', blob).getBytes(StandardCharsets.UTF_8))
                : Optional.<byte[]> empty()
    }

    @Override
    ExecHandle exec(ExecCommand command) {
        throw new UnsupportedOperationException('no process runs in a tip-tree box')
    }

    @Override
    void putFile(String path, byte[] content) {
        throw new UnsupportedOperationException('a tip-tree box is read-only')
    }

    @Override
    void harvest() {}

    @Override
    void dispose() {}

    @Override
    String scratchRoot() {
        throw new UnsupportedOperationException('a tip-tree box has no scratch area')
    }

    @Override
    CapabilityPassport passport() {
        CapabilityPassport.container()
    }
}
