package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Reusable Spock fixture: a working clone seeded with one commit, plus the {@code worktreesRoot}
 * git task branches are checked out under, and an {@link AttemptRecord} builder for round
 * fixtures. Factored out of the git and application adapters' usage/status specs, which each
 * repeated this same "seed a clone, build a round" setup (FR14, NFR-C1 of add-git-workflow).
 *
 * <p>Implementers provide {@code tempDir}, typically via Spock's {@code @TempDir}.
 */
trait SeededCloneFixture implements BareGitRepoFixture {

    abstract Path getTempDir()

    Path cloneDir
    Path worktreesRoot
    GitProcessRunner runner = new GitProcessRunner()

    void setupSeededClone() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    AttemptRecord round(int round, AttemptRecord.Result result, long wallMillis, long inputTokens) {
        new AttemptRecord(
                round,
                result,
                Instant.parse('2026-07-18T09:00:00Z').plusSeconds(round * 60),
                [] as List<CheckResult>,
                new ExecutorUsage(Duration.ofMillis(wallMillis), [],
                ['claude-x': new TokenUsage(inputTokens, 10, 0, 0)]),
                JudgeUsage.none(), [])
    }
}
